package com.bedrockproxy.native

/**
 * Paquete recibido desde RakNet.
 * [data] es una copia del payload; el buffer nativo ya fue liberado.
 */
class Packet(
    val data: ByteArray,
    val host: String,
    val port: Int,
    val timestamp: Long
) {
    val address: SystemAddress = SystemAddress(host, port)

    val packetId: Int = if (data.isNotEmpty()) data[0].toInt() and 0xFF else -1

    override fun toString(): String =
        "Packet(id=0x${packetId.toString(16).uppercase().padStart(2, '0')}, " +
            "len=${data.size}, from=$host:$port)"
}