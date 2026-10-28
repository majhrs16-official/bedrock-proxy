package com.bedrockproxy.native

/**
 * Dirección de un peer RakNet (IPv4).
 * Se usa como clave en mapas, por eso es un data class (equals/hashCode).
 */
data class SystemAddress(
    val host: String,
    val port: Int
) {
    override fun toString(): String = "$host:$port"
}