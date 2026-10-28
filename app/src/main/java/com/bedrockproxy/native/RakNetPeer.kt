package com.bedrockproxy.native

import com.bedrockproxy.util.AppLog

/**
 * Wrapper Kotlin sobre la implementación nativa de RakNet (JNI).
 *
 * Nota: [receive] debe ser llamado desde un único hilo por instancia.
 */
class RakNetPeer private constructor(private val nativeHandle: Long) {

    companion object {
        private var loaded = false

        @Synchronized
        fun loadLibrary() {
            if (loaded) return
            System.loadLibrary("raknet_jni")
            loaded = true
            AppLog.i("RakNet", "Biblioteca nativa raknet_jni cargada (protocolo 11)")
        }

        fun create(): RakNetPeer {
            loadLibrary()
            val handle = nativeCreate()
            check(handle != 0L) { "No se pudo crear RakNetPeer" }
            AppLog.d("RakNet", "Peer nativo creado (handle=$handle)")
            return RakNetPeer(handle)
        }

        @JvmStatic
        private external fun nativeCreate(): Long
    }

    data class StartupConfig(
        val port: Int = 0,
        val maxConnections: Int = 100,
        val offlinePingResponse: ByteArray? = null,
        /** Une el socket al multicast 224.0.2.60 para aparecer como mundo LAN. */
        val lanDiscovery: Boolean = false,
        /** IPv4 locales candidatas (Wi-Fi, hotspot, Ethernet…). Vacío = por defecto. */
        val lanInterfaceIps: List<String> = emptyList()
    )

    enum class PacketPriority(val value: Int) {
        IMMEDIATE_PRIORITY(0),
        HIGH_PRIORITY(1),
        MEDIUM_PRIORITY(2),
        LOW_PRIORITY(3)
    }

    enum class PacketReliability(val value: Int) {
        UNRELIABLE(0),
        UNRELIABLE_SEQUENCED(1),
        RELIABLE(2),
        RELIABLE_ORDERED(3),
        RELIABLE_SEQUENCED(4)
    }

    fun startup(config: StartupConfig): Boolean {
        val ok = nativeStartup(nativeHandle, config.port, config.maxConnections, config.offlinePingResponse)
        if (!ok) return false

        if (config.lanDiscovery) {
            val joined = joinLanDiscovery(config.lanInterfaceIps)
            if (joined) {
                AppLog.i("RakNet", "Descubrimiento LAN activo (224.0.2.60)")
            } else {
                AppLog.w("RakNet", "No se pudo activar el descubrimiento LAN todavía")
            }
        }
        return true
    }

    /**
     * (Re)une el socket al multicast LAN. Se puede llamar repetidamente:
     * si la interfaz ya estaba unida, el nativo lo trata como éxito.
     */
    fun joinLanDiscovery(ifaceIps: List<String>): Boolean = try {
        nativeJoinLanDiscovery(nativeHandle, ifaceIps.joinToString(","))
    } catch (t: Throwable) {
        AppLog.e("RakNet", "Error actualizando descubrimiento LAN", t)
        false
    }

    /**
     * Envía un pong MCPE por broadcast (por ejemplo 192.168.1.255:19132)
     * usando el mismo socket del listener. Es un canal alternativo al
     * multicast 224.0.2.60 para hotspots que filtran multicast.
     */
    fun sendBroadcastPong(host: String, port: Int, offlineResponse: ByteArray): Boolean = try {
        nativeSendBroadcastPong(nativeHandle, host, port, offlineResponse)
    } catch (t: Throwable) {
        AppLog.e("RakNet", "Error enviando pong broadcast a $host:$port", t)
        false
    }

    fun shutdown(timeoutSec: Double = 1.0) = nativeShutdown(nativeHandle, timeoutSec)

    fun connect(host: String, port: Int): Boolean = nativeConnect(nativeHandle, host, port)

    fun closeConnection(address: SystemAddress, sendDisconnectionNotification: Boolean = true) =
        nativeCloseConnection(nativeHandle, address.host, address.port, sendDisconnectionNotification)

    fun send(
        data: ByteArray,
        priority: PacketPriority,
        reliability: PacketReliability,
        orderingChannel: Int,
        address: SystemAddress
    ): Boolean = nativeSend(
        nativeHandle, data, priority.value, reliability.value,
        orderingChannel, address.host, address.port
    )

    fun receive(): Packet? = nativeReceive(nativeHandle)

    val boundPort: Int get() = nativeGetBoundPort(nativeHandle)

    val connectionCount: Int get() = nativeGetConnectionCount(nativeHandle)

    val isActive: Boolean get() = nativeIsActive(nativeHandle)

    fun setOfflinePingResponse(data: ByteArray?) = nativeSetOfflinePingResponse(nativeHandle, data)

    fun setMaxIncomingConnections(max: Int) = nativeSetMaxIncomingConnections(nativeHandle, max)

    fun ping(host: String, port: Int): Boolean = nativePing(nativeHandle, host, port)

    fun destroy() {
        AppLog.d("RakNet", "Destruyendo peer nativo (handle=$nativeHandle)")
        nativeDestroy(nativeHandle)
    }

    private external fun nativeStartup(
        handle: Long, port: Int, maxConnections: Int, offlinePingResponse: ByteArray?
    ): Boolean

    private external fun nativeShutdown(handle: Long, timeoutSec: Double)

    private external fun nativeConnect(handle: Long, host: String, port: Int): Boolean

    private external fun nativeCloseConnection(
        handle: Long, host: String, port: Int, sendDisconnectionNotification: Boolean
    )

    private external fun nativeSend(
        handle: Long, data: ByteArray, priority: Int, reliability: Int,
        orderingChannel: Int, host: String, port: Int
    ): Boolean

    private external fun nativeReceive(handle: Long): Packet?

    private external fun nativeGetBoundPort(handle: Long): Int

    private external fun nativeGetConnectionCount(handle: Long): Int

    private external fun nativeIsActive(handle: Long): Boolean

    private external fun nativeSetOfflinePingResponse(handle: Long, data: ByteArray?)

    private external fun nativeJoinLanDiscovery(handle: Long, ifaceIps: String): Boolean

    private external fun nativeSendBroadcastPong(
        handle: Long, host: String, port: Int, offlineResponse: ByteArray
    ): Boolean

    private external fun nativeSetMaxIncomingConnections(handle: Long, max: Int)

    private external fun nativePing(handle: Long, host: String, port: Int): Boolean

    private external fun nativeDestroy(handle: Long)
}