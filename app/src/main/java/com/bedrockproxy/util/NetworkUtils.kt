package com.bedrockproxy.util

import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Utilidades para mostrar al usuario la dirección desde la que debe
 * conectarse el cliente de Minecraft y para calcular los destinos LAN.
 */
object NetworkUtils {

    /**
     * Interfaz local con su IPv4 y los datos que usa el descubrimiento LAN.
     *
     * [broadcast] es la dirección de broadcast de la subred (por ejemplo
     * `192.168.1.255`); se usa como alternativa al multicast 224.0.2.60.
     */
    data class LocalInterface(
        val name: String,
        val address: String,
        val prefixLength: Int = 0,
        val broadcast: String? = null,
        val supportsMulticast: Boolean = false,
        val isPointToPoint: Boolean = false
    )

    /**
     * IPv4 de la interfaz Wi-Fi/Ethernet activa, que es la que usa el
     * descubrimiento LAN de Minecraft. Null si no hay (solo datos móviles).
     */
    fun wifiIpv4Address(): String? {
        return localInterfaces().firstOrNull { iface ->
            val n = iface.name.lowercase()
            n.startsWith("wlan") || n.startsWith("ap") ||
                n.startsWith("eth") || n.startsWith("swlan")
        }?.address
    }

    /**
     * Todas las interfaces con IPv4, ordenadas para que Wi-Fi/hotspot/Ethernet
     * salgan primero. El hotspot del teléfono suele llamarse `ap0`, `swlan0` o
     * `softap0`; por eso no basta con buscar `wlan*`.
     */
    fun localInterfaces(): List<LocalInterface> {
        val result = mutableListOf<LocalInterface>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name
                val lower = name.lowercase()
                if (lower == "lo" || lower.startsWith("dummy")) continue

                val addresses = java.util.Collections.list(iface.inetAddresses)
                val ipv4 = addresses.firstOrNull {
                    it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress
                } ?: continue
                val host = ipv4.hostAddress ?: continue

                val ifaceAddress = iface.interfaceAddresses.firstOrNull {
                    it.address is Inet4Address &&
                        it.address.hostAddress == host
                }
                val prefix = ifaceAddress?.networkPrefixLength?.toInt() ?: 24
                val broadcast = ifaceAddress?.broadcast?.hostAddress
                    ?: broadcastAddress(host, prefix)

                result.add(
                    LocalInterface(
                        name = name,
                        address = host,
                        prefixLength = prefix,
                        broadcast = broadcast,
                        supportsMulticast = try {
                            iface.supportsMulticast()
                        } catch (_: Exception) {
                            false
                        },
                        isPointToPoint = try {
                            iface.isPointToPoint
                        } catch (_: Exception) {
                            false
                        }
                    )
                )
            }
        } catch (_: Exception) {
            return emptyList()
        }
        return result
            .distinctBy { it.name }
            .sortedWith(compareBy({ interfaceRank(it.name) }, { it.name }))
    }

    /** IPv4 actual de una interfaz concreta (por nombre), o null si no está. */
    fun addressForInterface(name: String): String? {
        return localInterfaces().firstOrNull { it.name == name }?.address
    }

    /**
     * Interfaces que pueden recibir un broadcast LAN. En modo automático se
     * limitan a hotspot/Wi-Fi/Ethernet para no enviar broadcast por datos
     * móviles o Tailscale; si el usuario eligió una interfaz, se respeta.
     */
    fun broadcastTargets(selected: String? = null): List<LocalInterface> {
        val all = localInterfaces()
        val selectedIface = selected
            ?.takeIf { it.isNotBlank() }
            ?.let { name -> all.firstOrNull { it.name == name } }
        val candidates = if (selectedIface != null && interfaceRank(selectedIface.name) <= 2) {
            // La interfaz elegida es realmente LAN (hotspot/Wi-Fi/Ethernet).
            listOf(selectedIface)
        } else {
            // Si la elegida es datos móviles/VPN o ya no existe, usar la LAN
            // automática. Elegir ccmni/tun para descubrimiento LAN nunca sirve.
            all.filter { interfaceRank(it.name) <= 2 }
        }
        return candidates.filter { it.broadcast != null }
    }

    /** Describe las interfaces con máscara, broadcast y multicast para el log. */
    fun describeInterfaces(): String {
        return localInterfaces().joinToString("; ") {
            "${it.name}=${it.address}/${it.prefixLength} " +
                "bcast=${it.broadcast ?: "?"} mcast=${it.supportsMulticast}"
        }.ifBlank { "ninguna" }
    }

    /** Lee la tabla ARP si Android la expone; sirve para ver la IP del cliente. */
    fun arpNeighbors(): List<String> {
        return try {
            val file = File("/proc/net/arp")
            if (!file.canRead()) return emptyList()
            file.readLines().drop(1).mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size < 6) return@mapNotNull null
                val ip = parts[0]
                val flags = parts[2]
                val mac = parts[3]
                val device = parts[5]
                if (flags == "0x0" || mac == "00:00:00:00:00:00") null
                else "$ip ($mac, $device)"
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Ruta por defecto si `/proc/net/route` es legible. */
    fun defaultRoute(): String? {
        return try {
            val file = File("/proc/net/route")
            if (!file.canRead()) return null
            file.readLines().drop(1).firstOrNull { line ->
                line.trim().split(Regex("\\s+")).getOrNull(1) == "00000000"
            }?.let { line ->
                val parts = line.trim().split(Regex("\\s+"))
                val iface = parts.getOrNull(0) ?: return null
                val gateway = parts.getOrNull(2)?.let(::hexToIpv4) ?: "?"
                "$iface vía $gateway"
            }
        } catch (_: Exception) {
            null
        }
    }

    fun friendlyName(name: String): String {
        val n = name.lowercase()
        return when {
            n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("softap") -> "Hotspot"
            n.startsWith("wlan") -> "Wi-Fi"
            n.startsWith("eth") -> "Ethernet"
            n.startsWith("tailscale") || n.startsWith("tun") ||
                n.startsWith("wg") || n.startsWith("ppp") -> "VPN"
            n.startsWith("rmnet") || n.startsWith("ccmni") ||
                n.startsWith("radio") -> "Datos móviles"
            n.startsWith("p2p") -> "Wi-Fi Direct"
            else -> name
        }
    }

    private fun interfaceRank(name: String): Int {
        val n = name.lowercase()
        return when {
            n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("softap") -> 0
            n.startsWith("wlan") -> 1
            n.startsWith("eth") -> 2
            n.startsWith("tailscale") || n.startsWith("tun") ||
                n.startsWith("wg") || n.startsWith("ppp") -> 3
            n.startsWith("rmnet") || n.startsWith("ccmni") ||
                n.startsWith("radio") -> 4
            else -> 5
        }
    }

    private fun broadcastAddress(host: String, prefixLength: Int): String? {
        return try {
            val bytes = InetAddress.getByName(host).address
            if (bytes.size != 4) return null
            val ip = ((bytes[0].toLong() and 0xFF) shl 24) or
                ((bytes[1].toLong() and 0xFF) shl 16) or
                ((bytes[2].toLong() and 0xFF) shl 8) or
                (bytes[3].toLong() and 0xFF)
            val prefix = prefixLength.coerceIn(0, 32)
            val mask = if (prefix == 0) {
                0L
            } else {
                (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
            }
            val broadcast = ip or (mask.inv() and 0xFFFFFFFFL)
            "${(broadcast ushr 24) and 0xFF}." +
                "${(broadcast ushr 16) and 0xFF}." +
                "${(broadcast ushr 8) and 0xFF}." +
                "${broadcast and 0xFF}"
        } catch (_: Exception) {
            null
        }
    }

    private fun hexToIpv4(hex: String): String? {
        val value = hex.toLongOrNull(16) ?: return null
        return "${value and 0xFF}." +
            "${(value shr 8) and 0xFF}." +
            "${(value shr 16) and 0xFF}." +
            "${(value shr 24) and 0xFF}"
    }

    /** Devuelve las IPv4 locales no-loopback, priorizando hotspot/Wi-Fi/Ethernet. */
    fun localIpv4Addresses(): List<String> {
        return localInterfaces().map { it.address }
    }
}
