package com.bedrockproxy.core

import android.content.Context
import com.bedrockproxy.native.RakNetPeer
import com.bedrockproxy.native.SystemAddress
import com.bedrockproxy.util.AppLog
import com.bedrockproxy.util.NetworkUtils
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Servidor NetherNet (WebRTC) para clientes Bedrock 26.50+.
 *
 * Hace dos cosas sobre UDP 7551:
 *  1. Responde al descubrimiento LAN con una tarjeta de mundo (ServerData v7).
 *  2. Negocia un PeerConnection WebRTC con el cliente y lleva los paquetes
 *     Bedrock por el canal fiable hasta un RakNet remoto.
 *
 * El transporte NetherNet no cambia el protocolo Bedrock: los mensajes del
 * data channel son el batch de Bedrock sin el frame ID 0xFE que RakNet sí
 * espera. El puente añade o quita ese byte y no descifra el Login ni los
 * paquetes de juego.
 */
class NetherNetServer(
    private val context: Context,
    private val remoteHost: String,
    private val remotePort: Int,
    private val localMotd: String,
    private val maxConnections: Int,
    private val selectedInterface: () -> String?,
    private val onClientCount: (Int) -> Unit
) {

    companion object {
        private const val TAG = "NetherNet"
        private const val PORT = 7551
        private const val APPLICATION_ID = 0xDEADBEEFL
        private const val HMAC_ALGORITHM = "HmacSHA256"
        private const val AES_TRANSFORMATION = "AES/ECB/PKCS5Padding"
        private const val CHECKSUM_SIZE = 32
        private const val HEADER_SIZE = 20
        private const val MAX_SEGMENT_PAYLOAD = 9_999
        private const val MAX_SEGMENTS = 256
        private const val MAX_PENDING = 256
        private const val SERVER_DATA_VERSION = 7
        private const val CONNECTION_TYPE_LAN = 4

        private const val ID_CONNECTION_REQUEST_ACCEPTED = 0x10
        private const val ID_CONNECTION_ATTEMPT_FAILED = 0x11
        private const val ID_DISCONNECTION_NOTIFICATION = 0x15
        private const val ID_CONNECTION_LOST = 0x16

        val KEY: ByteArray = MessageDigest.getInstance("SHA-256").digest(
            ByteBuffer.allocate(8)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putLong(APPLICATION_ID)
                .array()
        )
    }

    data class Advertisement(
        val serverName: String,
        val levelName: String,
        val protocol: Int,
        val version: String,
        val players: Int,
        val maxPlayers: Int,
        val gameType: Int,
        val editorWorld: Boolean,
        val hardcore: Boolean,
        val acceptsOnlineAuth: Boolean,
        val acceptsSelfSignedAuth: Boolean,
        val nonce: String
    )

    private sealed interface Parsed {
        val senderId: Long

        data class Request(override val senderId: Long) : Parsed
        data class Response(override val senderId: Long, val data: ByteArray) : Parsed
        data class Message(
            override val senderId: Long,
            val recipientId: Long,
            val text: String
        ) : Parsed
    }

    private val running = AtomicBoolean(false)
    private val serverId: Long = randomNetworkId()
    private val nonce: String = randomHex(16)
    private val advertisement = AtomicReference(buildAdvertisement(null))

    private var socket: DatagramSocket? = null
    private var thread: Thread? = null
    private val sendLock = Any()

    /** Dirección UDP de cada red cliente, aprendida de sus paquetes. */
    private val addresses = ConcurrentHashMap<Long, InetSocketAddress>()

    /** Conexiones WebRTC activas, por connection ID. */
    private val connections = ConcurrentHashMap<Long, NetherConnection>()

    private val loggedSources: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private var totalPackets = 0L
    private var requestPackets = 0L
    private var messagePackets = 0L
    private var lastSummary = 0L
    private var startedAt = 0L
    private var warnedNoPackets = false

    lateinit var factory: PeerConnectionFactory
        private set

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return false

        return try {
            NetherNetIdentity.initialize(context)
            factory = WebRtcHolder.get(context)
            val s = DatagramSocket(null)
            s.reuseAddress = true
            s.broadcast = true
            s.bind(InetSocketAddress(PORT))
            s.soTimeout = 1_000
            socket = s
            startedAt = System.currentTimeMillis()
            lastSummary = startedAt
            warnedNoPackets = false
            AppLog.i(
                TAG,
                "NetherNet 7551 escuchando (mundo ${serverId.toString(16)}; " +
                    "transporte WebRTC + RakNet remoto)"
            )
            thread = Thread({ loop(s) }, "nethernet-server").apply {
                isDaemon = true
                start()
            }
            true
        } catch (t: Throwable) {
            running.set(false)
            try {
                socket?.close()
            } catch (_: Exception) {
            }
            socket = null
            AppLog.e(TAG, "No se pudo iniciar NetherNet 7551: ${t.javaClass.simpleName}: ${t.message}", t)
            false
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        val t = thread
        thread = null
        if (t != null && t != Thread.currentThread()) {
            t.interrupt()
            try {
                t.join(500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        socket = null

        connections.values.toList().forEach { it.close("servidor detenido") }
        connections.clear()
        addresses.clear()
        onClientCount(0)
        AppLog.d(TAG, "NetherNet 7551 detenido")
    }

    fun isRunning(): Boolean = running.get()

    fun updateAdvertisement(info: ServerPing.Info?) {
        val next = buildAdvertisement(info)
        val previous = advertisement.getAndSet(next)
        if (previous != next) {
            AppLog.i(
                TAG,
                "Anuncio NetherNet: \"${next.levelName}\" ${next.version} " +
                    "(protocolo ${next.protocol}), ${next.players}/${next.maxPlayers} jugadores, " +
                    "nonce ${next.nonce.take(8)}…"
            )
        }
    }

    private fun buildAdvertisement(info: ServerPing.Info?): Advertisement {
        val protocol = info?.protocol?.takeIf { it > 0 } ?: 2193
        val version = info?.version?.takeIf { it.isNotBlank() } ?: "26.52"
        val level = info?.motd?.takeIf { it.isNotBlank() } ?: localMotd
        val max = info?.maxPlayers?.takeIf { it > 0 } ?: maxConnections
        return Advertisement(
            serverName = "Bedrock Proxy",
            levelName = level,
            protocol = protocol,
            version = version,
            players = info?.players?.coerceAtLeast(0) ?: 0,
            maxPlayers = max,
            gameType = 0,
            editorWorld = false,
            hardcore = false,
            acceptsOnlineAuth = true,
            acceptsSelfSignedAuth = true,
            nonce = nonce
        )
    }

    // ------------------------------------------------------------- UDP loop

    private fun loop(s: DatagramSocket) {
        val buffer = ByteArray(65_535)
        while (running.get()) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                s.receive(packet)
            } catch (_: SocketTimeoutException) {
                warnIfSilent()
                continue
            } catch (t: Throwable) {
                if (running.get()) {
                    AppLog.w(TAG, "Error leyendo UDP 7551: ${t.javaClass.simpleName}")
                }
                break
            }

            if (packet.length > 0) {
                try {
                    handle(
                        buffer.copyOf(packet.length),
                        packet.address,
                        packet.port
                    )
                } catch (t: Throwable) {
                    AppLog.w(TAG, "Paquete NetherNet inválido: ${t.javaClass.simpleName}: ${t.message}")
                }
            }
        }
    }

    private fun handle(data: ByteArray, sourceAddress: java.net.InetAddress, sourcePort: Int) {
        totalPackets++
        val source = InetSocketAddress(sourceAddress, sourcePort)
        val parsed = parse(data)

        if (parsed == null) {
            if (loggedSources.add("raw:${source.hostString}:$sourcePort")) {
                AppLog.d(
                    TAG,
                    "Paquete UDP 7551 no reconocido de ${source.hostString}:$sourcePort " +
                        "(${data.size} bytes, ${hexPrefix(data)})"
                )
            }
            logSummaryIfNeeded()
            return
        }

        // Recordar la dirección de la red cliente para poder enviarle señales.
        addresses[parsed.senderId] = source

        when (parsed) {
            is Parsed.Request -> {
                requestPackets++
                if (loggedSources.add("req:${source.hostString}:$sourcePort")) {
                    AppLog.i(
                        TAG,
                        "Solicitud NetherNet de ${source.hostString}:$sourcePort " +
                            "(red ${parsed.senderId}); respondiendo con el mundo"
                    )
                }
                sendResponse(source)
            }

            is Parsed.Response -> Unit

            is Parsed.Message -> {
                messagePackets++
                if (parsed.recipientId != serverId) {
                    if (loggedSources.add("msg-other:${source.hostString}:$sourcePort")) {
                        AppLog.d(
                            TAG,
                            "Mensaje NetherNet para otra red ${parsed.recipientId} " +
                                "desde ${source.hostString}:$sourcePort"
                        )
                    }
                    logSummaryIfNeeded()
                    return
                }
                handleSignal(parsed.senderId, parsed.text, source)
            }
        }
        logSummaryIfNeeded()
    }

    private fun handleSignal(clientNetworkId: Long, text: String, source: InetSocketAddress) {
        if (text.isBlank() || text == "Ping") return
        val parts = text.split(' ', limit = 3)
        if (parts.size != 3) {
            if (loggedSources.add("signal-bad:${source.hostString}:$source")) {
                AppLog.d(TAG, "Señal NetherNet con formato inesperado: ${text.take(120)}")
            }
            return
        }
        val type = parts[0]
        val connectionId = parts[1].toLongOrNull() ?: return
        val payload = parts[2]

        when (type) {
            "CONNECTREQUEST" -> {
                AppLog.i(
                    TAG,
                    "Oferta NetherNet de ${source.hostString}:${source.port} " +
                        "(red $clientNetworkId, conexión $connectionId)"
                )
                if (connections.containsKey(connectionId)) {
                    AppLog.w(TAG, "La conexión $connectionId ya existe; oferta ignorada")
                    return
                }
                if (connections.size >= maxConnections) {
                    AppLog.w(TAG, "Máximo de conexiones NetherNet alcanzado (${connections.size})")
                    sendSignal(clientNetworkId, connectionId, "CONNECTERROR", "16")
                    return
                }
                val connection = try {
                    NetherConnection(clientNetworkId, connectionId, source)
                } catch (t: Throwable) {
                    AppLog.e(TAG, "No se pudo crear PeerConnection para $connectionId", t)
                    sendSignal(clientNetworkId, connectionId, "CONNECTERROR", "4")
                    return
                }
                connections[connectionId] = connection
                onClientCount(connections.size)
                connection.handleOffer(payload)
            }

            "CANDIDATEADD" -> {
                val connection = connections[connectionId]
                if (connection == null) {
                    AppLog.d(TAG, "Candidato ICE para conexión desconocida $connectionId")
                } else {
                    connection.addRemoteCandidate(payload)
                }
            }

            "CONNECTERROR" -> {
                AppLog.w(TAG, "El cliente NetherNet reportó error $payload (conexión $connectionId)")
                connections[connectionId]?.close("error del cliente $payload")
            }

            else -> AppLog.d(TAG, "Señal NetherNet desconocida: $type")
        }
    }

    private fun sendResponse(target: InetSocketAddress) {
        val ad = advertisement.get()
        val serverData = ServerDataCodec.encode(ad)
        val packet = encodeResponse(serverData)
        send(packet, target)
        if (loggedSources.add("resp:${target.hostString}:${target.port}")) {
            AppLog.d(TAG, "Respuesta NetherNet enviada a ${target.hostString}:${target.port}")
        }
    }

    private fun sendSignal(clientNetworkId: Long, connectionId: Long, type: String, data: String) {
        val target = addresses[clientNetworkId] ?: run {
            AppLog.d(TAG, "Sin dirección para la red $clientNetworkId; no se puede enviar $type")
            return
        }
        val text = "$type $connectionId $data"
        send(encodeMessage(clientNetworkId, text), target)
    }

    private fun send(data: ByteArray, target: InetSocketAddress) {
        synchronized(sendLock) {
            try {
                socket?.send(DatagramPacket(data, data.size, target))
            } catch (t: Throwable) {
                if (running.get()) {
                    AppLog.w(TAG, "Error enviando UDP 7551 a $target: ${t.javaClass.simpleName}")
                }
                Unit
            }
        }
    }

    private fun warnIfSilent() {
        if (warnedNoPackets || totalPackets > 0) return
        if (System.currentTimeMillis() - startedAt < 30_000) return
        warnedNoPackets = true
        AppLog.w(
            TAG,
            "NetherNet 7551: sin paquetes en 30 s. Si el cliente está en la misma " +
                "red, revisa que el AP reenvíe broadcast y que Minecraft esté en " +
                "26.50 o superior."
        )
    }

    private fun logSummaryIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastSummary < 60_000) return
        lastSummary = now
        AppLog.d(
            TAG,
            "NetherNet 7551: $totalPackets paquete(s) " +
                "(peticiones=$requestPackets, señales=$messagePackets, " +
                "conexiones=${connections.size})"
        )
    }

    // ------------------------------------------------------------- packets

    private fun parse(data: ByteArray): Parsed? {
        if (data.size < CHECKSUM_SIZE + 16) return null
        val receivedMac = data.copyOfRange(0, CHECKSUM_SIZE)
        val cipherText = data.copyOfRange(CHECKSUM_SIZE, data.size)
        val plain = decrypt(cipherText) ?: return null
        if (!MessageDigest.isEqual(receivedMac, hmac(plain))) return null
        if (plain.size < HEADER_SIZE) return null

        val r = Reader(plain)
        r.u16() // longitud declarada
        val packetId = r.u16()
        val senderId = r.u64()
        r.take(8) // reservado

        return when (packetId) {
            0 -> Parsed.Request(senderId)
            1 -> Parsed.Response(senderId, hexToBytes(r.prefixedString()))
            2 -> Parsed.Message(senderId, r.u64(), r.prefixedString())
            else -> null
        }
    }

    private fun encodeResponse(serverData: ByteArray): ByteArray {
        val body = Writer()
        body.u16(1)
        body.u64(serverId)
        body.bytes(ByteArray(8))
        val text = hex(serverData).toByteArray(Charsets.UTF_8)
        body.u32(text.size)
        body.bytes(text)

        val payload = Writer()
        payload.u16(body.size() + 2)
        payload.bytes(body.data())
        return hmac(payload.data()) + encrypt(payload.data())
    }

    private fun encodeMessage(recipientId: Long, text: String): ByteArray {
        val body = Writer()
        body.u16(2)
        body.u64(serverId)
        body.bytes(ByteArray(8))
        body.u64(recipientId)
        val bytes = text.toByteArray(Charsets.UTF_8)
        body.u32(bytes.size)
        body.bytes(bytes)

        val payload = Writer()
        payload.u16(body.size() + 2)
        payload.bytes(body.data())
        return hmac(payload.data()) + encrypt(payload.data())
    }

    private fun decrypt(cipherText: ByteArray): ByteArray? = try {
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(KEY, "AES"))
        cipher.doFinal(cipherText)
    } catch (_: Throwable) {
        null
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(KEY, "AES"))
        return cipher.doFinal(plain)
    }

    private fun hmac(payload: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(KEY, HMAC_ALGORITHM))
        return mac.doFinal(payload)
    }

    private fun randomNetworkId(): Long {
        val bytes = ByteArray(8)
        SecureRandom().nextBytes(bytes)
        var value = 0L
        for (i in 7 downTo 0) {
            value = (value shl 8) or (bytes[i].toLong() and 0xFF)
        }
        return value
    }

    private fun randomHex(count: Int): String {
        val bytes = ByteArray(count)
        SecureRandom().nextBytes(bytes)
        return hex(bytes)
    }

    private fun hex(data: ByteArray): String {
        val chars = CharArray(data.size * 2)
        val digits = "0123456789abcdef"
        for (i in data.indices) {
            val v = data[i].toInt() and 0xFF
            chars[i * 2] = digits[v ushr 4]
            chars[i * 2 + 1] = digits[v and 0x0F]
        }
        return String(chars)
    }

    private fun hexToBytes(text: String): ByteArray {
        require(text.length % 2 == 0) { "hex impar" }
        val out = ByteArray(text.length / 2)
        for (i in out.indices) {
            out[i] = ((hexDigit(text[i * 2]) shl 4) or hexDigit(text[i * 2 + 1])).toByte()
        }
        return out
    }

    private fun hexDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw IllegalArgumentException("hex inválido")
    }

    private fun hexPrefix(data: ByteArray): String =
        data.take(12).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun countSdpLines(sdp: String, prefix: String): Int =
        sdp.split(Regex("\r\n|\n")).count { it.trim().startsWith(prefix) }

    // --------------------------------------------------------- WebRTC peer

    private inner class NetherConnection(
        private val clientNetworkId: Long,
        private val connectionId: Long,
        private val remoteAddress: InetSocketAddress
    ) {
        private val closed = AtomicBoolean(false)
        private val answerSent = AtomicBoolean(false)
        private val firstToRemote = AtomicBoolean(false)
        private val firstToNether = AtomicBoolean(false)
        private val localCandidates = ConcurrentLinkedQueue<String>()
        private val pendingToRemote = ConcurrentLinkedQueue<ByteArray>()
        private val pendingToNether = ConcurrentLinkedQueue<ByteArray>()
        private val reassembly = ByteArrayOutputStream()
        private val reassemblyLock = Any()
        private val sendLock = Any()

        @Volatile
        private var reassemblyExpected = 0

        @Volatile
        private var reliable: DataChannel? = null

        @Volatile
        private var unreliable: DataChannel? = null

        @Volatile
        private var rakPeer: RakNetPeer? = null

        @Volatile
        private var serverAddress: SystemAddress? = null

        @Volatile
        private var remoteConnected = false

        @Volatile
        private var rakThread: Thread? = null

        @Volatile
        private var rakStarted = false

        private val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                AppLog.d(TAG, "ICE/$connectionId signaling=$state")
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                AppLog.d(TAG, "ICE/$connectionId estado=$state")
                if (state == PeerConnection.IceConnectionState.FAILED ||
                    state == PeerConnection.IceConnectionState.CLOSED
                ) {
                    close("ICE $state")
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {
                AppLog.d(TAG, "ICE/$connectionId receiving=$receiving")
            }

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                AppLog.d(TAG, "ICE/$connectionId gathering=$state")
            }

            override fun onIceCandidate(candidate: IceCandidate?) {
                onLocalCandidate(candidate)
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
            }

            override fun onAddStream(stream: org.webrtc.MediaStream?) {
            }

            override fun onRemoveStream(stream: org.webrtc.MediaStream?) {
            }

            override fun onDataChannel(channel: DataChannel?) {
                if (channel == null) return
                AppLog.i(TAG, "DataChannel/$connectionId: ${channel.label()} (${channel.state()})")
                when (channel.label()) {
                    "ReliableDataChannel" -> {
                        if (reliable != null) {
                            AppLog.w(TAG, "Canal fiable duplicado en $connectionId")
                            channel.close()
                            return
                        }
                        reliable = channel
                        channel.registerObserver(dataChannelObserver(channel, reliable = true))
                        if (channel.state() == DataChannel.State.OPEN) {
                            onReliableOpen()
                        }
                    }

                    "UnreliableDataChannel" -> {
                        unreliable = channel
                        channel.registerObserver(dataChannelObserver(channel, reliable = false))
                    }

                    else -> {
                        AppLog.w(TAG, "Canal NetherNet inesperado: ${channel.label()}")
                        channel.close()
                    }
                }
            }

            override fun onRenegotiationNeeded() {
                AppLog.d(TAG, "ICE/$connectionId renegociación solicitada")
            }
        }

        private val pc: PeerConnection = factory.createPeerConnection(
            buildRtcConfiguration(),
            observer
        ) ?: throw IllegalStateException("PeerConnectionFactory devolvió null")

        init {
            Thread({
                sleepQuietly(45_000)
                if (!closed.get() && !remoteConnected) {
                    AppLog.w(TAG, "NetherNet $connectionId sin puente tras 45 s; cerrando")
                    close("timeout de negociación")
                }
            }, "nethernet-watchdog-$connectionId").apply {
                isDaemon = true
                start()
            }
        }

        fun handleOffer(sdp: String) {
            val clean = sanitizeOffer(sdp)
            AppLog.d(
                TAG,
                "Oferta SDP/$connectionId: ${clean.length} bytes, " +
                    "candidatos=${countSdpLines(clean, "a=candidate:")}, " +
                    "identidad=${sdp.contains("a=identity:")}"
            )
            pc.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) = Unit

                override fun onSetSuccess() {
                    if (closed.get()) return
                    addCandidatesFromSdp(clean)
                    createAnswer()
                }

                override fun onCreateFailure(error: String?) {
                    fail("setRemoteDescription: $error")
                }

                override fun onSetFailure(error: String?) {
                    fail("setRemoteDescription: $error")
                }
            }, SessionDescription(SessionDescription.Type.OFFER, clean))
        }

        private fun createAnswer() {
            pc.createAnswer(object : SdpObserver {
                override fun onCreateSuccess(answer: SessionDescription?) {
                    if (answer == null) {
                        fail("createAnswer devolvió null")
                        return
                    }
                    if (closed.get()) return
                    val plainAnswer = stripCandidates(answer.description)
                    val signedAnswer = try {
                        NetherNetIdentity.attach(plainAnswer)
                    } catch (t: Throwable) {
                        AppLog.w(
                            TAG,
                            "No se pudo firmar la identidad NetherNet; se enviará sin a=identity: " +
                                "${t.javaClass.simpleName}: ${t.message}"
                        )
                        plainAnswer
                    }
                    val local = SessionDescription(
                        SessionDescription.Type.ANSWER,
                        signedAnswer
                    )
                    pc.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(desc: SessionDescription?) = Unit

                        override fun onSetSuccess() {
                            sendAnswer(signedAnswer)
                        }

                        override fun onCreateFailure(error: String?) {
                            fail("setLocalDescription: $error")
                        }

                        override fun onSetFailure(error: String?) {
                            if (signedAnswer != plainAnswer) {
                                AppLog.w(
                                    TAG,
                                    "WebRTC rechazó el SDP con a=identity ($error); " +
                                        "reintentando sin el atributo y enviándolo igualmente"
                                )
                                pc.setLocalDescription(object : SdpObserver {
                                    override fun onCreateSuccess(desc: SessionDescription?) = Unit

                                    override fun onSetSuccess() {
                                        sendAnswer(signedAnswer)
                                    }

                                    override fun onCreateFailure(other: String?) {
                                        fail("setLocalDescription sin identidad: $other")
                                    }

                                    override fun onSetFailure(other: String?) {
                                        fail("setLocalDescription sin identidad: $other")
                                    }
                                }, SessionDescription(SessionDescription.Type.ANSWER, plainAnswer))
                            } else {
                                fail("setLocalDescription: $error")
                            }
                        }
                    }, local)
                }

                override fun onSetSuccess() = Unit

                override fun onCreateFailure(error: String?) {
                    fail("createAnswer: $error")
                }

                override fun onSetFailure(error: String?) = Unit
            }, MediaConstraints())
        }

        private fun sendAnswer(sdp: String) {
            if (closed.get() || !answerSent.compareAndSet(false, true)) return
            sendSignal(clientNetworkId, connectionId, "CONNECTRESPONSE", sdp)
            AppLog.i(
                TAG,
                "Answer NetherNet enviado a $remoteAddress " +
                    "(conexión $connectionId, ${sdp.length} bytes, " +
                    "identidad=${sdp.contains("a=identity:")})"
            )
            flushLocalCandidates()
        }

        private fun onLocalCandidate(candidate: IceCandidate?) {
            val sdp = candidate?.sdp ?: return
            val originalIp = candidateAddress(sdp) ?: return
            if (originalIp.contains(':')) return // IPv6 no sirve en esta LAN IPv4
            val allowed = allowedAddresses()
            if (allowed.isEmpty()) {
                AppLog.w(TAG, "Candidato ICE sin IP LAN utilizable: $originalIp")
                return
            }
            val preferred = localAddressForClient(remoteAddress.address?.hostAddress)
            val chosenIp = when {
                preferred != null && preferred != originalIp -> preferred
                allowed.contains(originalIp) -> originalIp
                else -> preferred ?: allowed.first()
            }
            val rewritten = if (chosenIp != originalIp) {
                AppLog.d(TAG, "Candidato ICE reescrito: $originalIp → $chosenIp")
                rewriteCandidateAddress(sdp, chosenIp)
            } else {
                sdp
            }
            val formatted = formatCandidate(rewritten)
            localCandidates.add(formatted)
            if (answerSent.get()) flushLocalCandidates()
        }

        private fun flushLocalCandidates() {
            if (!answerSent.get()) return
            while (true) {
                val candidate = localCandidates.poll() ?: break
                sendSignal(clientNetworkId, connectionId, "CANDIDATEADD", candidate)
            }
        }

        fun addRemoteCandidate(candidate: String) {
            if (closed.get()) return
            try {
                val clean = candidate.trim().removePrefix("a=")
                val ok = pc.addIceCandidate(IceCandidate("0", 0, clean))
                AppLog.d(TAG, "Candidato ICE remoto ${if (ok) "aceptado" else "rechazado"} en $connectionId")
            } catch (t: Throwable) {
                AppLog.w(TAG, "Candidato ICE remoto inválido en $connectionId: ${t.message}")
            }
        }

        private fun addCandidatesFromSdp(sdp: String) {
            for (line in sdp.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("a=candidate:")) {
                    addRemoteCandidate(trimmed.substring(2))
                }
            }
        }

        private fun dataChannelObserver(channel: DataChannel, reliable: Boolean) =
            object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) {
                }

                override fun onStateChange() {
                    AppLog.d(
                        TAG,
                        "DataChannel/$connectionId ${channel.label()} → ${channel.state()}"
                    )
                    if (reliable && channel.state() == DataChannel.State.OPEN) {
                        onReliableOpen()
                    }
                    if (channel.state() == DataChannel.State.CLOSED && reliable) {
                        close("canal fiable cerrado")
                    }
                }

                override fun onMessage(buffer: DataChannel.Buffer?) {
                    if (buffer == null) return
                    val data = ByteArray(buffer.data.remaining())
                    buffer.data.get(data)
                    if (reliable) {
                        onNetherMessage(data)
                    }
                }
            }

        private fun onReliableOpen() {
            AppLog.i(TAG, "Canal fiable abierto para $remoteAddress (conexión $connectionId)")
            startRakNet()
            flushPendingToNether()
        }

        private fun startRakNet() {
            if (rakStarted || closed.get()) return
            rakStarted = true
            val peer = try {
                RakNetPeer.create()
            } catch (t: Throwable) {
                AppLog.e(TAG, "No se pudo crear RakNet para NetherNet", t)
                close("RakNet no disponible")
                return
            }
            rakPeer = peer
            if (!peer.startup(RakNetPeer.StartupConfig(port = 0, maxConnections = 1))) {
                AppLog.e(TAG, "No se pudo iniciar RakNet para NetherNet")
                close("RakNet startup falló")
                return
            }
            AppLog.d(TAG, "Conectando NetherNet $connectionId → $remoteHost:$remotePort")
            if (!peer.connect(remoteHost, remotePort)) {
                AppLog.e(TAG, "No se pudo conectar NetherNet $connectionId al remoto")
                close("conexión remota falló")
                return
            }
            rakThread = Thread({ rakLoop(peer) }, "nethernet-rak-$connectionId").apply {
                isDaemon = true
                start()
            }
        }

        private fun rakLoop(peer: RakNetPeer) {
            while (!closed.get() && running.get()) {
                val packet = try {
                    peer.receive()
                } catch (t: Throwable) {
                    if (!closed.get()) {
                        AppLog.e(TAG, "Error receive() RakNet NetherNet", t)
                    }
                    null
                }
                if (packet == null) {
                    sleepQuietly(1)
                    continue
                }

                when (packet.packetId) {
                    ID_CONNECTION_REQUEST_ACCEPTED -> {
                        serverAddress = packet.address
                        remoteConnected = true
                        AppLog.i(TAG, "Puente NetherNet listo: $remoteAddress ↔ ${packet.address}")
                        flushPendingToRemote()
                    }

                    ID_CONNECTION_ATTEMPT_FAILED -> {
                        AppLog.e(TAG, "El remoto rechazó la conexión NetherNet $connectionId")
                        close("remoto rechazó")
                    }

                    ID_DISCONNECTION_NOTIFICATION, ID_CONNECTION_LOST -> {
                        AppLog.i(TAG, "El remoto cerró la conexión NetherNet $connectionId")
                        close("remoto cerró")
                    }

                    else -> {
                        val server = serverAddress ?: continue
                        if (packet.address != server) continue
                        sendToNether(withoutBatchHeader(packet.data))
                    }
                }
            }
        }

        private fun onNetherMessage(data: ByteArray) {
            if (data.isEmpty()) return
            if (closed.get()) return

            synchronized(reassemblyLock) {
                val remaining = data[0].toInt() and 0xFF
                if (reassemblyExpected > 0 && reassemblyExpected - 1 != remaining) {
                    AppLog.w(
                        TAG,
                        "Segmentos NetherNet fuera de orden en $connectionId " +
                            "(esperado ${reassemblyExpected - 1}, recibido $remaining)"
                    )
                    close("segmentos fuera de orden")
                    return
                }
                reassemblyExpected = remaining
                reassembly.write(data, 1, data.size - 1)
                if (remaining > 0) return

                val payload = reassembly.toByteArray()
                reassembly.reset()
                reassemblyExpected = 0
                if (payload.isEmpty()) return
                // NetherNet manda el batch de Bedrock sin el frame ID 0xFE.
                // RakNet sí lo espera, así que se lo añadimos al puente.
                forwardToRemote(withBatchHeader(payload))
            }
        }

        private fun forwardToRemote(payload: ByteArray) {
            if (firstToRemote.compareAndSet(false, true)) {
                AppLog.d(
                    TAG,
                    "NetherNet→RakNet primer batch: ${payload.size} bytes, " +
                        "inicio=${hexPrefix(payload)}"
                )
            }
            val server = serverAddress
            val peer = rakPeer
            if (!remoteConnected || server == null || peer == null) {
                if (pendingToRemote.size < MAX_PENDING) {
                    pendingToRemote.add(payload)
                } else {
                    AppLog.w(TAG, "Cola NetherNet→RakNet llena; paquete descartado")
                }
                return
            }
            if (!peer.send(
                    payload,
                    RakNetPeer.PacketPriority.HIGH_PRIORITY,
                    RakNetPeer.PacketReliability.RELIABLE_ORDERED,
                    0,
                    server
                )
            ) {
                AppLog.w(TAG, "RakNet rechazó un paquete NetherNet (${payload.size} bytes)")
            }
        }

        private fun flushPendingToRemote() {
            val server = serverAddress ?: return
            val peer = rakPeer ?: return
            var sent = 0
            while (true) {
                val payload = pendingToRemote.poll() ?: break
                if (peer.send(
                        payload,
                        RakNetPeer.PacketPriority.HIGH_PRIORITY,
                        RakNetPeer.PacketReliability.RELIABLE_ORDERED,
                        0,
                        server
                    )
                ) {
                    sent++
                }
            }
            if (sent > 0) {
                AppLog.d(TAG, "Reenviados $sent paquete(s) NetherNet en cola")
            }
        }

        private fun sendToNether(payload: ByteArray) {
            if (firstToNether.compareAndSet(false, true)) {
                AppLog.d(
                    TAG,
                    "RakNet→NetherNet primer batch: ${payload.size} bytes, " +
                        "inicio=${hexPrefix(payload)}"
                )
            }
            val channel = reliable
            if (channel == null || channel.state() != DataChannel.State.OPEN) {
                if (pendingToNether.size < MAX_PENDING) {
                    pendingToNether.add(payload)
                }
                return
            }
            if (payload.isEmpty()) return

            val total = maxOf(1, (payload.size + MAX_SEGMENT_PAYLOAD - 1) / MAX_SEGMENT_PAYLOAD)
            if (total > MAX_SEGMENTS) {
                AppLog.w(TAG, "Paquete de ${payload.size} bytes necesita $total segmentos; descartado")
                return
            }

            synchronized(sendLock) {
                var offset = 0
                for (index in 0 until total) {
                    val end = minOf(offset + MAX_SEGMENT_PAYLOAD, payload.size)
                    val segment = ByteArray(1 + (end - offset))
                    segment[0] = (total - 1 - index).toByte()
                    System.arraycopy(payload, offset, segment, 1, end - offset)
                    val ok = channel.send(
                        DataChannel.Buffer(ByteBuffer.wrap(segment), true)
                    )
                    if (!ok) {
                        AppLog.w(TAG, "DataChannel rechazó segmento ${index + 1}/$total")
                        break
                    }
                    offset = end
                }
            }
        }

        private fun flushPendingToNether() {
            while (true) {
                val payload = pendingToNether.poll() ?: break
                sendToNether(payload)
            }
        }

        private fun fail(message: String) {
            AppLog.e(TAG, "Fallo NetherNet $connectionId: $message")
            sendSignal(clientNetworkId, connectionId, "CONNECTERROR", "4")
            close(message)
        }

        fun close(reason: String) {
            if (!closed.compareAndSet(false, true)) return
            AppLog.i(TAG, "Cerrando NetherNet $connectionId ($remoteAddress): $reason")
            try {
                reliable?.close()
            } catch (_: Exception) {
            }
            try {
                unreliable?.close()
            } catch (_: Exception) {
            }
            try {
                pc.close()
            } catch (_: Exception) {
            }
            try {
                pc.dispose()
            } catch (_: Exception) {
            }

            val thread = rakThread
            rakThread = null
            thread?.interrupt()
            try {
                rakPeer?.shutdown(0.2)
            } catch (_: Exception) {
            }
            try {
                rakPeer?.destroy()
            } catch (_: Exception) {
            }
            rakPeer = null
            serverAddress = null
            remoteConnected = false
            reliable = null
            unreliable = null
            pendingToRemote.clear()
            pendingToNether.clear()

            connections.remove(connectionId, this)
            onClientCount(connections.size)
        }
    }

    private fun buildRtcConfiguration(): PeerConnection.RTCConfiguration {
        val config = PeerConnection.RTCConfiguration(emptyList())
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        config.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        config.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        config.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
        config.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        config.disableIPv6OnWifi = true
        // Permite reunir candidatos en sockets 0.0.0.0:puerto. Es clave en el
        // hotspot: la interfaz ap0 puede no aparecer como "red" de Android,
        // pero el socket comodín sí recibe paquetes dirigidos a su IP.
        config.enableIceGatheringOnAnyAddressPorts = true
        return config
    }

    private fun sanitizeOffer(sdp: String): String {
        // El atributo a=identity puede venir con una identidad de Xbox que no
        // necesitamos verificar en LAN. El stack WebRTC no lo interpreta; se
        // retira para que no confunda al parser.
        return sdp.lineSequence()
            .filterNot { it.trimStart().startsWith("a=identity:") }
            .joinToString("\r\n") + "\r\n"
    }

    private fun stripCandidates(sdp: String): String {
        return sdp.lineSequence()
            .filterNot {
                val t = it.trimStart()
                t.startsWith("a=candidate:") || t.startsWith("a=end-of-candidates")
            }
            .joinToString("\r\n") + "\r\n"
    }

    /**
     * IP local de la misma subred que el cliente NetherNet. Evita anunciar la
     * IP de Wi-Fi cuando el cliente está en el hotspot (o al revés).
     */
    private fun localAddressForClient(clientIp: String?): String? {
        if (clientIp.isNullOrBlank()) return null
        val client = try {
            InetAddress.getByName(clientIp)
        } catch (_: Exception) {
            return null
        }
        val clientBytes = client.address
        if (clientBytes.size != 4) return null
        val allowed = allowedAddresses()
        val candidates = NetworkUtils.localInterfaces().filter { it.address in allowed }
        return candidates.firstOrNull { iface ->
            val local = try {
                InetAddress.getByName(iface.address).address
            } catch (_: Exception) {
                return@firstOrNull false
            }
            if (local.size != 4) return@firstOrNull false
            val prefix = iface.prefixLength.coerceIn(0, 32)
            val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
            val a = ((local[0].toInt() and 0xFF) shl 24) or
                ((local[1].toInt() and 0xFF) shl 16) or
                ((local[2].toInt() and 0xFF) shl 8) or
                (local[3].toInt() and 0xFF)
            val b = ((clientBytes[0].toInt() and 0xFF) shl 24) or
                ((clientBytes[1].toInt() and 0xFF) shl 16) or
                ((clientBytes[2].toInt() and 0xFF) shl 8) or
                (clientBytes[3].toInt() and 0xFF)
            (a and mask) == (b and mask)
        }?.address ?: candidates.firstOrNull()?.address
    }

    private fun rewriteCandidateAddress(sdp: String, newIp: String): String {
        val fields = sdp.trim()
            .removePrefix("a=")
            .removePrefix("candidate:")
            .split(' ')
            .filter { it.isNotBlank() }
            .toMutableList()
        if (fields.size < 5) return sdp
        fields[4] = newIp
        return "candidate:" + fields.joinToString(" ")
    }

    private fun candidateAddress(sdp: String): String? {
        val fields = sdp.trim().removePrefix("a=").removePrefix("candidate:")
            .split(' ').filter { it.isNotBlank() }
        return fields.getOrNull(4)
    }

    private fun formatCandidate(sdp: String): String {
        var body = sdp.trim().removePrefix("a=").removePrefix("candidate:")
        val fields = body.split(' ').filter { it.isNotBlank() }
        if (fields.size < 8) return "candidate:$body"
        val hasGeneration = fields.contains("generation")
        val hasUfrag = fields.contains("ufrag")
        val hasNetworkId = fields.contains("network-id")
        val hasNetworkCost = fields.contains("network-cost")
        if (hasGeneration && hasUfrag && hasNetworkId && hasNetworkCost) {
            return "candidate:$body"
        }
        val ufrag = if (hasUfrag) {
            fields.getOrNull(fields.indexOf("ufrag") + 1) ?: "0"
        } else {
            "0"
        }
        val networkId = if (hasNetworkId) {
            fields.getOrNull(fields.indexOf("network-id") + 1) ?: "1"
        } else {
            "1"
        }
        if (!hasGeneration) body += " generation 0"
        if (!hasUfrag) body += " ufrag $ufrag"
        if (!hasNetworkId) body += " network-id $networkId"
        if (!hasNetworkCost) body += " network-cost 0"
        return "candidate:$body"
    }

    private fun allowedAddresses(): Set<String> {
        val selected = selectedInterface()?.takeIf { it.isNotBlank() }
        val targets = NetworkUtils.broadcastTargets(selected)
        if (targets.isNotEmpty()) {
            return targets.map { it.address }.toSet()
        }
        // Sin interfaz LAN reconocible, permitir las direcciones locales para
        // no bloquear un caso exótico; el cliente solo podrá usar la que le
        // sea alcanzable.
        return NetworkUtils.localIpv4Addresses().toSet()
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // -------------------------------------------------------------- codecs

    /**
     * Añade el frame ID 0xFE que RakNet espera antes del batch de Bedrock.
     * NetherNet envía el batch comprimido sin ese byte (Geyser llama a este
     * formato "compressed batch without RakNet's 0xFE frame ID").
     */
    private fun withBatchHeader(payload: ByteArray): ByteArray {
        if (payload.firstOrNull() == 0xFE.toByte()) return payload
        val out = ByteArray(payload.size + 1)
        out[0] = 0xFE.toByte()
        System.arraycopy(payload, 0, out, 1, payload.size)
        return out
    }

    /** Quita el frame ID 0xFE de un batch RakNet antes de enviarlo por NetherNet. */
    private fun withoutBatchHeader(payload: ByteArray): ByteArray {
        if (payload.firstOrNull() != 0xFE.toByte()) return payload
        return payload.copyOfRange(1, payload.size)
    }

    private class Writer {
        private val out = ByteArrayOutputStream(256)

        fun u8(value: Int) {
            out.write(value and 0xFF)
        }

        fun u16(value: Int) {
            out.write(value and 0xFF)
            out.write((value ushr 8) and 0xFF)
        }

        fun u32(value: Int) {
            out.write(value and 0xFF)
            out.write((value ushr 8) and 0xFF)
            out.write((value ushr 16) and 0xFF)
            out.write((value ushr 24) and 0xFF)
        }

        fun u64(value: Long) {
            for (i in 0 until 8) {
                out.write(((value ushr (i * 8)) and 0xFF).toInt())
            }
        }

        fun bool(value: Boolean) = u8(if (value) 1 else 0)

        fun bytes(value: ByteArray) {
            out.write(value)
        }

        fun varUInt(value: Long) {
            var remaining = value
            while (remaining >= 0x80) {
                out.write(((remaining and 0x7F) or 0x80).toInt())
                remaining = remaining ushr 7
            }
            out.write(remaining.toInt())
        }

        fun varInt(value: Int) {
            val zigzag = (value shl 1) xor (value shr 31)
            varUInt(zigzag.toLong() and 0xFFFFFFFFL)
        }

        fun string(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            varUInt(bytes.size.toLong())
            bytes(bytes)
        }

        fun data(): ByteArray = out.toByteArray()

        fun size(): Int = out.size()
    }

    private class Reader(private val data: ByteArray) {
        private var offset = 0

        fun remaining(): Int = data.size - offset

        fun u8(): Int {
            require(remaining() >= 1)
            return data[offset++].toInt() and 0xFF
        }

        fun u16(): Int {
            require(remaining() >= 2)
            val value = (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8)
            offset += 2
            return value
        }

        fun u32(): Int {
            require(remaining() >= 4)
            var value = 0
            for (i in 3 downTo 0) {
                value = (value shl 8) or (data[offset + i].toInt() and 0xFF)
            }
            offset += 4
            return value
        }

        fun u64(): Long {
            require(remaining() >= 8)
            var value = 0L
            for (i in 7 downTo 0) {
                value = (value shl 8) or (data[offset + i].toLong() and 0xFF)
            }
            offset += 8
            return value
        }

        fun take(count: Int): ByteArray {
            require(count >= 0 && count <= remaining())
            val out = data.copyOfRange(offset, offset + count)
            offset += count
            return out
        }

        fun string(): String {
            val length = varUInt().toInt()
            require(length >= 0 && length <= remaining())
            return String(take(length), Charsets.UTF_8)
        }

        fun prefixedString(): String {
            val length = u32()
            require(length >= 0 && length <= remaining())
            return String(take(length), Charsets.UTF_8)
        }

        fun varUInt(): Long {
            var value = 0L
            var shift = 0
            while (shift < 35) {
                val b = u8()
                value = value or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return value
                shift += 7
            }
            throw IllegalArgumentException("varuint demasiado largo")
        }

        fun varInt(): Int {
            val raw = varUInt()
            return (raw ushr 1).toInt() xor -((raw and 1).toInt())
        }

        fun bool(): Boolean = u8() != 0
    }

    private object ServerDataCodec {
        fun encode(ad: Advertisement): ByteArray {
            val w = Writer()
            w.u8(SERVER_DATA_VERSION)
            w.string(ad.serverName)
            w.varInt(ad.protocol)
            w.string(ad.version)
            w.string(ad.levelName)
            w.varInt(ad.players)
            w.varInt(ad.maxPlayers)
            w.varInt(ad.gameType)
            w.bool(ad.editorWorld)
            w.bool(ad.hardcore)
            w.bool(ad.acceptsOnlineAuth)
            w.bool(ad.acceptsSelfSignedAuth)
            w.string(ad.nonce)
            w.varInt(CONNECTION_TYPE_LAN)
            return w.data()
        }
    }

    private object WebRtcHolder {
        @Volatile
        private var factory: PeerConnectionFactory? = null

        @Synchronized
        fun get(context: Context): PeerConnectionFactory {
            factory?.let { return it }
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )
            val created = PeerConnectionFactory.builder().createPeerConnectionFactory()
            factory = created
            return created
        }
    }
}
