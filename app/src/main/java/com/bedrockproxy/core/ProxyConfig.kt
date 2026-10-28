package com.bedrockproxy.core

import android.os.Parcel
import android.os.Parcelable

data class ProxyConfig(
    val remoteHost: String = "127.0.0.1",
    val remotePort: Int = 19133,
    val localPort: Int = 19132,
    val motd: String = "Bedrock Proxy",
    val maxConnections: Int = 100,
    /** Nombre de la interfaz para el descubrimiento LAN; vacío = automática. */
    val lanInterface: String = ""
) : Parcelable {
    constructor(parcel: Parcel) : this(
        remoteHost = parcel.readString() ?: "127.0.0.1",
        remotePort = parcel.readInt(),
        localPort = parcel.readInt(),
        motd = parcel.readString() ?: "Bedrock Proxy",
        maxConnections = parcel.readInt(),
        lanInterface = parcel.readString() ?: ""
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(remoteHost)
        parcel.writeInt(remotePort)
        parcel.writeInt(localPort)
        parcel.writeString(motd)
        parcel.writeInt(maxConnections)
        parcel.writeString(lanInterface)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<ProxyConfig> {
        override fun createFromParcel(parcel: Parcel): ProxyConfig = ProxyConfig(parcel)
        override fun newArray(size: Int): Array<ProxyConfig?> = arrayOfNulls(size)
    }

    /**
     * Construye el pong MCPE que se anuncia por LAN.
     *
     * Si [info] no es null se refleja el servidor remoto real (protocolo,
     * versión, jugadores, MOTD...) para que el cliente no lo marque como
     * incompatible. Los puertos siempre son los locales: el cliente debe
     * conectar contra este proxy, no contra el remoto.
     */
    fun buildOfflinePingResponse(info: ServerPing.Info? = null): ByteArray {
        // Formato oficial MCPE del unconnected pong:
        // MCPE;motd;protocolo;versión;jugadores;max;serverId;motd2;gamemode;gamemodeId;puertoV4;puertoV6;
        val safeMotd = motd.replace(';', ' ').ifBlank { "Bedrock Proxy" }
        // Fallback para cuando el remoto aún no responde: la versión estable
        // actual de Bedrock (26.52). Si el remoto responde se usan sus datos.
        val protocol = info?.protocol ?: 2193
        val version = info?.version ?: "26.52"
        val players = info?.players ?: 0
        val maxPlayers = info?.maxPlayers?.takeIf { it > 0 } ?: maxConnections
        val serverId = info?.serverId ?: "123456789012345678"
        val motd2 = info?.subMotd ?: "LAN ${remoteHost}:${remotePort}"
        val gamemode = info?.gameMode ?: "Survival"
        val gamemodeId = info?.gameModeId ?: 1
        val port = localPort.toString()

        val response =
            "MCPE;$safeMotd;$protocol;$version;$players;$maxPlayers;$serverId;" +
                "$motd2;$gamemode;$gamemodeId;$port;$port;"

        // Bedrock espera un uint16 BE con la longitud del string antes del "MCPE;".
        val payload = response.toByteArray(Charsets.UTF_8)
        val length = minOf(payload.size, 0xFFFF)
        val framed = ByteArray(2 + length)
        framed[0] = ((length ushr 8) and 0xFF).toByte()
        framed[1] = (length and 0xFF).toByte()
        System.arraycopy(payload, 0, framed, 2, length)
        return framed
    }

    fun validate(): ValidationResult {
        val errors = mutableListOf<String>()

        if (remoteHost.isBlank()) {
            errors.add("IP remota no puede estar vacía")
        } else if (!isValidHost(remoteHost)) {
            errors.add("IP o dominio inválido: $remoteHost")
        }

        if (remotePort !in 1..65535) {
            errors.add("Puerto remoto debe estar entre 1 y 65535")
        }

        if (localPort !in 1..65535) {
            errors.add("Puerto local debe estar entre 1 y 65535")
        }

        if (maxConnections !in 1..1000) {
            errors.add("Máximo de conexiones debe estar entre 1 y 1000")
        }

        return if (errors.isEmpty()) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(errors)
        }
    }

    /** Acepta IPv4 o un nombre de dominio (RakNet resuelve el host por DNS). */
    private fun isValidHost(host: String): Boolean {
        val value = host.trim()
        if (value.isEmpty() || value.length > 253) return false
        return value.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
    }

    sealed interface ValidationResult {
        data object Valid : ValidationResult
        data class Invalid(val errors: List<String>) : ValidationResult
    }
}