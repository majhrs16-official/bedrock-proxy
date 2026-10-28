package com.bedrockproxy.core

import android.content.Context
import com.bedrockproxy.native.Packet
import com.bedrockproxy.native.RakNetPeer
import com.bedrockproxy.native.SystemAddress
import com.bedrockproxy.util.AppLog
import com.bedrockproxy.util.NetworkUtils
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Proxy transparente de Minecraft Bedrock.
 *
 * - Un RakPeer "listener" acepta conexiones de clientes.
 * - Por cada cliente se crea un RakPeer "cliente" que se conecta al servidor destino.
 * - Un único hilo recibe del listener y reenvía al servidor de cada cliente.
 * - Un hilo por cliente recibe del servidor y reenvía al cliente.
 */
class BedrockProxy(
    private val context: Context,
    private val config: ProxyConfig,
    private val stateCallback: (ProxyState) -> Unit
) {
    companion object {
        private const val TAG = "BedrockProxy"

        const val ID_CONNECTION_REQUEST_ACCEPTED = 0x10
        const val ID_CONNECTION_ATTEMPT_FAILED = 0x11
        const val ID_NEW_INCOMING_CONNECTION = 0x13
        const val ID_DISCONNECTION_NOTIFICATION = 0x15
        const val ID_CONNECTION_LOST = 0x16

        private const val MAX_PENDING_PACKETS = 256
    }

    private class ClientConnection(
        val clientAddress: SystemAddress,
        val serverPeer: RakNetPeer
    ) {
        @Volatile var serverAddress: SystemAddress? = null
        @Volatile var connected = false
        @Volatile var stopping = false
        var serverThread: Thread? = null

        /** Paquetes del cliente recibidos antes de completar el puente. */
        val pending = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
    }

    private val running = AtomicBoolean(false)
    private val listenerPeer = java.util.concurrent.atomic.AtomicReference<RakNetPeer?>(null)
    private val clients = ConcurrentHashMap<SystemAddress, ClientConnection>()
    private val clientCount = AtomicInteger(0)

    private var listenerThread: Thread? = null
    private var pingThread: Thread? = null
    private var broadcastThread: Thread? = null
    private var serverPing: ServerPing? = null
    private var netherNetServer: NetherNetServer? = null

    @Volatile
    private var netherNetClients = 0

    /** Último pong LAN; el hilo de broadcast lo envía cada 5 s. */
    @Volatile
    private var offlineResponse: ByteArray = config.buildOfflinePingResponse()

    /** @return true si el listener quedó escuchando. */
    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return false

        AppLog.i(
            TAG,
            "Iniciando proxy local ${config.localPort} → " +
                "${config.remoteHost}:${config.remotePort}"
        )
        AppLog.d(TAG, "Interfaces detalladas: ${NetworkUtils.describeInterfaces()}")
        AppLog.d(TAG, "Ruta por defecto: ${NetworkUtils.defaultRoute() ?: "no disponible"}")
        AppLog.d(
            TAG,
            "Vecinos ARP: " +
                NetworkUtils.arpNeighbors().joinToString().ifBlank { "sin entradas" }
        )

        val peer = try {
            RakNetPeer.create()
        } catch (t: Throwable) {
            AppLog.e(TAG, "No se pudo crear el peer RakNet", t)
            running.set(false)
            stateCallback(ProxyState.Error("Motor RakNet no disponible (${t.javaClass.simpleName})"))
            return false
        }
        AppLog.d(TAG, "Peer RakNet del listener creado")

        AppLog.d(TAG, "Abriendo puerto UDP ${config.localPort}…")
        if (config.localPort != 19132) {
            AppLog.w(
                TAG,
                "El descubrimiento LAN de Minecraft usa el puerto 19132; " +
                    "con ${config.localPort} el mundo no aparecerá automáticamente"
            )
        }
        val selectedIface = config.lanInterface.takeIf { it.isNotBlank() }
        val lanIps = lanInterfaceIps()
        val lanReady = selectedIface == null || lanIps.isNotEmpty()
        val interfaceDetails = NetworkUtils.localInterfaces()
        val selectedDetails = selectedIface?.let { name ->
            interfaceDetails.firstOrNull { it.name == name }
        }
        if (selectedIface != null) {
            val isRealLan = NetworkUtils.broadcastTargets(selectedIface)
                .any { it.name == selectedIface }
            if (lanIps.isEmpty()) {
                AppLog.w(TAG, "Interfaz LAN '$selectedIface' no disponible; se reintentará")
            } else if (selectedDetails != null) {
                AppLog.i(
                    TAG,
                    "Interfaz LAN: ${selectedDetails.name} " +
                        "(${selectedDetails.address}/${selectedDetails.prefixLength}), " +
                        "broadcast ${selectedDetails.broadcast ?: "no disponible"}, " +
                        "multicast=${selectedDetails.supportsMulticast}"
                )
                if (!isRealLan) {
                    AppLog.w(
                        TAG,
                        "La interfaz '$selectedIface' no es hotspot/Wi-Fi/Ethernet; " +
                            "el descubrimiento LAN usará la interfaz automática"
                    )
                }
            } else {
                AppLog.i(TAG, "Interfaz LAN: $selectedIface (${lanIps.first()})")
            }
        } else if (lanIps.isEmpty()) {
            AppLog.w(TAG, "No hay IPv4 locales; el descubrimiento LAN usará la interfaz por defecto")
        } else {
            AppLog.d(TAG, "IPv4 LAN candidatas: ${lanIps.joinToString()}")
        }

        val initialResponse = config.buildOfflinePingResponse()
        offlineResponse = initialResponse

        val startupOk = try {
            peer.startup(
                RakNetPeer.StartupConfig(
                    port = config.localPort,
                    maxConnections = config.maxConnections,
                    offlinePingResponse = initialResponse,
                    lanDiscovery = lanReady,
                    lanInterfaceIps = lanIps
                )
            )
        } catch (t: Throwable) {
            AppLog.e(TAG, "Error iniciando RakNet", t)
            false
        }

        if (!startupOk) {
            peer.destroy()
            running.set(false)
            stateCallback(ProxyState.Error("No se pudo abrir el puerto ${config.localPort}"))
            return false
        }

        AppLog.i(TAG, "Puerto local ${config.localPort} abierto")
        listenerPeer.set(peer)
        listenerThread = Thread({ listenerLoop(peer) }, "proxy-listener").apply {
            isDaemon = true
            start()
        }

        // NetherNet: descubrimiento y puente WebRTC para Minecraft 26.50+.
        netherNetServer = NetherNetServer(
            context = context,
            remoteHost = config.remoteHost,
            remotePort = config.remotePort,
            localMotd = config.motd,
            maxConnections = config.maxConnections,
            selectedInterface = { config.lanInterface.takeIf { it.isNotBlank() } },
            onClientCount = { count ->
                netherNetClients = count
                publishCount()
            }
        ).also { server ->
            if (!server.start()) {
                AppLog.w(TAG, "NetherNet no disponible; los clientes 26.50+ no verán el mundo")
            }
        }

        netherNetServer?.updateAdvertisement(null)
        startPingRefresh(peer)
        startBroadcastBeacon(peer)

        stateCallback(ProxyState.Running(config.localPort, 0))
        AppLog.d(TAG, "Hilo listener iniciado; esperando clientes")
        return true
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return

        AppLog.i(TAG, "Deteniendo proxy (${clients.size} cliente/s activos)…")

        // Detener el ping remoto antes de destruir el peer del listener.
        serverPing?.cancel()
        serverPing = null
        val pinger = pingThread
        pingThread = null
        pinger?.interrupt()
        if (pinger != null && pinger != Thread.currentThread()) {
            try {
                pinger.join(1500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        val broadcaster = broadcastThread
        broadcastThread = null
        broadcaster?.interrupt()
        if (broadcaster != null && broadcaster != Thread.currentThread()) {
            try {
                broadcaster.join(500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        netherNetServer?.stop()
        netherNetServer = null

        clients.values.forEach { stopClient(it, notifyClient = true) }
        clients.clear()
        clientCount.set(0)

        val thread = listenerThread
        listenerThread = null
        thread?.interrupt()
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        listenerPeer.getAndSet(null)?.let { peer ->
            try {
                peer.shutdown(0.5)
            } catch (_: Exception) {
            }
            peer.destroy()
        }

        stateCallback(ProxyState.Stopped)
        AppLog.i(TAG, "Proxy detenido")
    }

    // ---------------------------------------------------------------- listener

    /**
     * IPv4 de la interfaz elegida por el usuario; si está vacía, todas las
     * locales (modo automático).
     */
    private fun lanInterfaceIps(): List<String> {
        val selected = config.lanInterface.takeIf { it.isNotBlank() }
        if (selected != null) {
            val targets = NetworkUtils.broadcastTargets(selected)
            if (targets.isNotEmpty()) return targets.map { it.address }
            AppLog.w(
                TAG,
                "La interfaz '$selected' no es una LAN válida; usando detección automática"
            )
        }
        return NetworkUtils.localIpv4Addresses()
    }

    /** Reintenta el join multicast; si la interfaz elegida aún no existe, espera. */
    private fun refreshLanJoin(peer: RakNetPeer) {
        val selected = config.lanInterface.takeIf { it.isNotBlank() }
        val ips = lanInterfaceIps()
        if (selected != null && ips.isEmpty()) return
        peer.joinLanDiscovery(ips)
    }

    /**
     * Consulta periódicamente el servidor remoto y actualiza el pong LAN con
     * sus datos reales (protocolo, versión, jugadores, MOTD).
     */
    private fun startPingRefresh(peer: RakNetPeer) {
        val pinger = ServerPing(config.remoteHost, config.remotePort)
        serverPing = pinger

        pingThread = Thread({
            var lastKey: String? = null
            var reachable = true
            var lastArpLog = 0L

            while (running.get()) {
                // El hotspot/Wi-Fi puede aparecer después de arrancar: refrescar
                // el join multicast en la interfaz elegida (es idempotente).
                refreshLanJoin(peer)

                val now = System.currentTimeMillis()
                if (now - lastArpLog >= 60_000) {
                    lastArpLog = now
                    val registered = clients.keys.joinToString().ifBlank { "ninguno" }
                    val neighbors = NetworkUtils.arpNeighbors()
                        .joinToString().ifBlank { "sin entradas" }
                    AppLog.d(
                        TAG,
                        "Clientes registrados: $registered; ARP: $neighbors"
                    )
                }

                val info = pinger.query()

                // El peer puede haberse destruido mientras consultábamos.
                if (!running.get()) break

                if (info != null) {
                    val response = config.buildOfflinePingResponse(info)
                    offlineResponse = response
                    peer.setOfflinePingResponse(response)
                    netherNetServer?.updateAdvertisement(info)
                    val key = "${info.protocol}|${info.version}|${info.players}|" +
                        "${info.maxPlayers}|${info.motd}"
                    if (key != lastKey) {
                        AppLog.i(
                            TAG,
                            "Anuncio LAN: ${info.version} (protocolo ${info.protocol}), " +
                                "${info.players}/${info.maxPlayers} jugadores, " +
                                "MOTD \"${info.motd}\""
                        )
                        lastKey = key
                    }
                    if (!reachable) {
                        AppLog.i(TAG, "Servidor remoto accesible de nuevo")
                        reachable = true
                    }
                } else {
                    if (reachable) {
                        AppLog.w(
                            TAG,
                            "Sin ping de ${config.remoteHost}:${config.remotePort}; " +
                                "se mantiene el último anuncio LAN"
                        )
                        reachable = false
                    }
                }

                sleepQuietly(5000)
            }
            AppLog.d(TAG, "Hilo de ping remoto terminado")
        }, "proxy-ping").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Canal alternativo al multicast: envía el pong MCPE a la dirección de
     * broadcast de cada interfaz LAN (p. ej. 192.168.1.255:19132) cada 5 s.
     * Algunos hotspots filtran multicast pero sí reenvían broadcast.
     */
    private fun startBroadcastBeacon(peer: RakNetPeer) {
        broadcastThread = Thread({
            var lastSummary = 0L
            while (running.get()) {
                val selected = config.lanInterface.takeIf { it.isNotBlank() }
                val targets = NetworkUtils.broadcastTargets(selected)
                if (targets.isNotEmpty()) {
                    val payload = offlineResponse
                    var sent = 0
                    for (iface in targets) {
                        val broadcast = iface.broadcast ?: continue
                        if (peer.sendBroadcastPong(broadcast, config.localPort, payload)) {
                            sent++
                        }
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastSummary >= 60_000) {
                        lastSummary = now
                        AppLog.d(
                            TAG,
                            "Broadcast LAN: " +
                                targets.joinToString { "${it.name}→${it.broadcast}" } +
                                " ($sent/${targets.size} ok)"
                        )
                    }
                } else {
                    val now = System.currentTimeMillis()
                    if (now - lastSummary >= 60_000) {
                        lastSummary = now
                        AppLog.d(
                            TAG,
                            "Broadcast LAN: sin interfaces con broadcast (se reintentará)"
                        )
                    }
                }
                sleepQuietly(5000)
            }
            AppLog.d(TAG, "Hilo broadcast terminado")
        }, "proxy-broadcast").apply {
            isDaemon = true
            start()
        }
    }

    private fun listenerLoop(peer: RakNetPeer) {
        while (running.get()) {
            val packet = try {
                peer.receive()
            } catch (e: Exception) {
                AppLog.e(TAG, "Error en receive() del listener", e)
                null
            }

            if (packet != null) {
                try {
                    handleListenerPacket(packet)
                } catch (e: Exception) {
                    AppLog.e(TAG, "Error procesando paquete de ${packet.address}", e)
                }
            } else {
                sleepQuietly(1)
            }
        }
    }

    private fun handleListenerPacket(packet: Packet) {
        when (packet.packetId) {
            ID_NEW_INCOMING_CONNECTION -> onNewClient(packet.address)
            ID_DISCONNECTION_NOTIFICATION, ID_CONNECTION_LOST -> onClientGone(packet.address)
            else -> forwardToServer(packet)
        }
    }

    private fun onNewClient(clientAddress: SystemAddress) {
        AppLog.i(TAG, "Nuevo cliente conectado: $clientAddress")

        if (clients.containsKey(clientAddress)) {
            AppLog.w(TAG, "El cliente $clientAddress ya estaba registrado")
            return
        }

        val serverPeer = RakNetPeer.create()
        AppLog.d(TAG, "Peer del servidor creado para $clientAddress")
        val ok = serverPeer.startup(
            RakNetPeer.StartupConfig(port = 0, maxConnections = 1)
        )

        if (!ok) {
            AppLog.e(TAG, "No se pudo iniciar peer para $clientAddress")
            serverPeer.destroy()
            refuseClient(clientAddress)
            return
        }

        val client = ClientConnection(clientAddress, serverPeer)
        clients[clientAddress] = client

        AppLog.d(TAG, "Conectando $clientAddress → ${config.remoteHost}:${config.remotePort}")
        if (!serverPeer.connect(config.remoteHost, config.remotePort)) {
            AppLog.e(TAG, "No se pudo iniciar conexión a ${config.remoteHost}:${config.remotePort}")
            clients.remove(clientAddress)
            stopClient(client, notifyClient = false)
            refuseClient(clientAddress)
            return
        }

        client.serverThread = Thread({ serverLoop(client) }, "proxy-server-${clientAddress.port}").apply {
            isDaemon = true
            start()
        }
    }

    private fun refuseClient(clientAddress: SystemAddress) {
        AppLog.w(TAG, "Rechazando cliente $clientAddress")
        listenerPeer.get()?.send(
            byteArrayOf(ID_CONNECTION_ATTEMPT_FAILED.toByte()),
            RakNetPeer.PacketPriority.IMMEDIATE_PRIORITY,
            RakNetPeer.PacketReliability.RELIABLE,
            0,
            clientAddress
        )
    }

    // ----------------------------------------------------------- server -> client

    private fun serverLoop(client: ClientConnection) {
        val serverPeer = client.serverPeer

        while (running.get() && !client.stopping) {
            val packet = try {
                serverPeer.receive()
            } catch (e: Exception) {
                AppLog.e(TAG, "Error en receive() del servidor de ${client.clientAddress}", e)
                null
            }

            if (packet == null) {
                sleepQuietly(1)
                continue
            }

            when (packet.packetId) {
                ID_CONNECTION_REQUEST_ACCEPTED -> {
                    client.serverAddress = packet.address
                    flushPending(client)
                    client.connected = true
                    AppLog.i(TAG, "Puente listo: ${client.clientAddress} ↔ ${packet.address}")
                    publishCount()
                }

                ID_CONNECTION_ATTEMPT_FAILED -> {
                    AppLog.e(TAG, "El servidor remoto rechazó a ${client.clientAddress}")
                    onClientGone(client.clientAddress)
                }

                ID_DISCONNECTION_NOTIFICATION, ID_CONNECTION_LOST -> {
                    AppLog.i(TAG, "El servidor cerró la conexión de ${client.clientAddress}")
                    onClientGone(client.clientAddress)
                }

                else -> {
                    val serverAddr = client.serverAddress ?: continue
                    if (packet.address != serverAddr) continue

                    listenerPeer.get()?.send(
                        packet.data,
                        RakNetPeer.PacketPriority.HIGH_PRIORITY,
                        RakNetPeer.PacketReliability.RELIABLE_ORDERED,
                        0,
                        client.clientAddress
                    )
                }
            }
        }
    }

    // ----------------------------------------------------------- client -> server

    private fun forwardToServer(packet: Packet) {
        val client = clients[packet.address] ?: return
        val serverAddr = client.serverAddress

        if (!client.connected || serverAddr == null) {
            if (client.pending.size < MAX_PENDING_PACKETS) {
                client.pending.add(packet.data)
            } else {
                AppLog.w(TAG, "Cola llena para ${client.clientAddress}; paquete descartado")
            }
            return
        }

        client.serverPeer.send(
            packet.data,
            RakNetPeer.PacketPriority.HIGH_PRIORITY,
            RakNetPeer.PacketReliability.RELIABLE_ORDERED,
            0,
            serverAddr
        )
    }

    /** Envía al servidor los paquetes que llegaron antes de completar el puente. */
    private fun flushPending(client: ClientConnection) {
        val serverAddr = client.serverAddress ?: return
        var sent = 0
        while (true) {
            val data = client.pending.poll() ?: break
            client.serverPeer.send(
                data,
                RakNetPeer.PacketPriority.HIGH_PRIORITY,
                RakNetPeer.PacketReliability.RELIABLE_ORDERED,
                0,
                serverAddr
            )
            sent++
        }
        if (sent > 0) {
            AppLog.i(TAG, "Reenviados $sent paquete(s) en cola de ${client.clientAddress}")
        }
    }

    // ------------------------------------------------------------------ cleanup

    private fun onClientGone(clientAddress: SystemAddress) {
        val client = clients.remove(clientAddress) ?: return
        AppLog.i(TAG, "Cliente desconectado: $clientAddress")
        stopClient(client, notifyClient = true)
        publishCount()
    }

    private fun stopClient(client: ClientConnection, notifyClient: Boolean) {
        client.stopping = true

        try {
            if (notifyClient && client.connected) {
                listenerPeer.get()?.closeConnection(client.clientAddress, true)
            }
        } catch (_: Exception) {
        }

        val thread = client.serverThread
        client.serverThread = null
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt()
            try {
                thread.join(300)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        try {
            client.serverPeer.shutdown(0.2)
        } catch (_: Exception) {
        }

        try {
            client.serverPeer.destroy()
        } catch (_: Exception) {
        }
    }

    private fun publishCount() {
        val total = clients.size + netherNetClients
        clientCount.set(total)
        AppLog.d(
            TAG,
            "Clientes activos: $total (RakNet=${clients.size}, NetherNet=$netherNetClients)"
        )
        stateCallback(ProxyState.Running(config.localPort, total))
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}