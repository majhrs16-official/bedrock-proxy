package com.bedrockproxy.core

import com.bedrockproxy.util.AppLog
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Consulta el ping "unconnected" de un servidor Bedrock remoto.
 *
 * Se usa para reflejar en el anuncio LAN los datos reales del servidor
 * (protocolo, versión, jugadores, MOTD) en lugar de valores fijos.
 * El cliente de Minecraft compara la versión/protocolo del pong: si no
 * coinciden, marca el mundo como incompatible y no deja entrar.
 */
class ServerPing(private val host: String, private val port: Int) {

    data class Info(
        val motd: String,
        val protocol: Int,
        val version: String,
        val players: Int,
        val maxPlayers: Int,
        val serverId: String,
        val subMotd: String,
        val gameMode: String,
        val gameModeId: Int
    )

    @Volatile
    private var socket: DatagramSocket? = null

    @Volatile
    private var cancelled = false

    /** @return datos del servidor o null si no respondió / respuesta inválida. */
    fun query(timeoutMs: Int = 2500): Info? {
        if (cancelled) return null

        var s: DatagramSocket? = null
        return try {
            s = DatagramSocket()
            synchronized(this) {
                if (cancelled) {
                    s.close()
                    return null
                }
                socket = s
            }
            s.soTimeout = timeoutMs

            val address = InetAddress.getByName(host)
            val request = buildRequest()
            s.send(DatagramPacket(request, request.size, address, port))

            val buffer = ByteArray(2048)
            val response = DatagramPacket(buffer, buffer.size)
            s.receive(response)
            parse(buffer, response.length)
        } catch (e: Exception) {
            if (!cancelled) {
                AppLog.d(TAG, "Ping remoto falló (${e.javaClass.simpleName})")
            }
            null
        } finally {
            synchronized(this) { socket = null }
            try {
                s?.close()
            } catch (_: Exception) {
            }
        }
    }

    /** Cierra el socket en curso para que [query] termine de inmediato. */
    fun cancel() {
        cancelled = true
        synchronized(this) {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
            socket = null
        }
    }

    private fun buildRequest(): ByteArray {
        // ID_UNCONNECTED_PING + timestamp + magic + client GUID
        val buffer = ByteBuffer.allocate(1 + 8 + MAGIC.size + 8)
            .order(ByteOrder.BIG_ENDIAN)
        buffer.put(ID_UNCONNECTED_PING)
        buffer.putLong(System.currentTimeMillis())
        buffer.put(MAGIC)
        buffer.putLong(UUID.randomUUID().mostSignificantBits)
        return buffer.array()
    }

    private fun parse(buffer: ByteArray, length: Int): Info? {
        // ID_UNCONNECTED_PONG (1) + pingTime (8) + serverGuid (8) + magic (16)
        if (length <= PONG_HEADER) return null
        if (buffer[0] != ID_UNCONNECTED_PONG) return null

        // Bedrock: uint16 BE con la longitud del string MCPE;… (RakLib/Cloudburst).
        // Se tolera también el formato sin longitud por si algún servidor no lo pone.
        var offset = PONG_HEADER
        var payloadLength = length - PONG_HEADER
        if (length >= PONG_HEADER + 2) {
            val declared = ((buffer[PONG_HEADER].toInt() and 0xFF) shl 8) or
                (buffer[PONG_HEADER + 1].toInt() and 0xFF)
            if (declared in 1..(length - PONG_HEADER - 2) &&
                buffer[PONG_HEADER + 2] == 'M'.code.toByte()
            ) {
                offset = PONG_HEADER + 2
                payloadLength = declared
            }
        }

        val text = String(buffer, offset, payloadLength, Charsets.UTF_8)
        if (!text.startsWith("MCPE;")) return null

        val fields = text.split(';')
        if (fields.size < 12) return null

        return Info(
            motd = fields[1],
            protocol = fields[2].toIntOrNull() ?: return null,
            version = fields[3],
            players = fields[4].toIntOrNull() ?: 0,
            maxPlayers = fields[5].toIntOrNull() ?: 0,
            serverId = fields[6],
            subMotd = fields[7],
            gameMode = fields[8],
            gameModeId = fields[9].toIntOrNull() ?: 1
        )
    }

    companion object {
        private const val TAG = "ServerPing"
        private const val ID_UNCONNECTED_PING: Byte = 0x01
        private const val ID_UNCONNECTED_PONG: Byte = 0x1c
        private const val PONG_HEADER = 33

        private val MAGIC = byteArrayOf(
            0x00, 0xFF.toByte(), 0xFF.toByte(), 0x00,
            0xFE.toByte(), 0xFE.toByte(), 0xFE.toByte(), 0xFE.toByte(),
            0xFD.toByte(), 0xFD.toByte(), 0xFD.toByte(), 0xFD.toByte(),
            0x12, 0x34, 0x56, 0x78
        )
    }
}
