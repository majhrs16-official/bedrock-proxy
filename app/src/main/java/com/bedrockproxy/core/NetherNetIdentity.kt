package com.bedrockproxy.core

import android.content.Context
import android.util.Base64
import com.bedrockproxy.util.AppLog
import org.json.JSONObject
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Identidad NetherNet del servidor.
 *
 * El cliente Bedrock puede exigir que la respuesta SDP lleve un atributo
 * `a=identity`. Sin él, algunos clientes responden CONNECTERROR 37
 * (`IdentityNotAllowed`) y no llegan a abrir los data channels.
 *
 * El formato es el de Bedrock Dedicated Server:
 *  - clave EC P-384 (secp384r1);
 *  - token JWT autocontenido `ES384` con la clave pública en `cpk`;
 *  - JWS separado `ES384` sobre el JSON de los fingerprints DTLS del SDP;
 *  - sobre `{"assertion":"...","idp":{"domain":"self","protocol":"default"}}`.
 *
 * La clave se guarda en el almacenamiento privado de la app y el token se
 * reemite en cada conexión porque dura un minuto.
 */
object NetherNetIdentity {

    private const val TAG = "NetherNetId"
    private const val DOMAIN = "self"
    private const val TOKEN_LIFETIME_SECONDS = 60L
    private const val P384_COMPONENT_SIZE = 48
    private const val PRIVATE_FILE = "nethernet_identity.pk8"
    private const val PUBLIC_FILE = "nethernet_identity.x509"

    @Volatile
    private var keyPair: KeyPair? = null

    /**
     * Carga la clave persistida o genera una nueva. Mantener la misma clave
     * entre reinicios evita que el cliente vea una identidad distinta cada vez
     * (trust on first use).
     */
    @Synchronized
    fun initialize(context: Context) {
        if (keyPair != null) return
        val dir = context.applicationContext.filesDir
        val privateFile = File(dir, PRIVATE_FILE)
        val publicFile = File(dir, PUBLIC_FILE)
        keyPair = try {
            load(privateFile, publicFile) ?: generateAndSave(privateFile, publicFile)
        } catch (t: Throwable) {
            AppLog.w(TAG, "No se pudo cargar la identidad NetherNet: ${t.message}")
            generateAndSave(privateFile, publicFile)
        }
        AppLog.i(TAG, "Identidad NetherNet lista (EC P-384, persistente)")
    }

    private fun currentKeyPair(): KeyPair {
        val current = keyPair
        if (current != null) return current
        throw IllegalStateException("NetherNetIdentity no inicializada")
    }

    private fun load(privateFile: File, publicFile: File): KeyPair? {
        if (!privateFile.isFile || !publicFile.isFile) return null
        val factory = KeyFactory.getInstance("EC")
        val privateKey = factory.generatePrivate(
            PKCS8EncodedKeySpec(privateFile.readBytes())
        )
        val publicKey = factory.generatePublic(
            X509EncodedKeySpec(publicFile.readBytes())
        )
        return KeyPair(publicKey, privateKey)
    }

    private fun generateAndSave(privateFile: File, publicFile: File): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp384r1"))
        val generated = generator.generateKeyPair()
        privateFile.writeBytes(generated.private.encoded)
        publicFile.writeBytes(generated.public.encoded)
        return generated
    }

    fun attach(sdp: String): String {
        val fingerprints = extractFingerprints(sdp)
        require(fingerprints.isNotEmpty()) { "el SDP no tiene a=fingerprint" }

        val payload = fingerprintPayload(fingerprints)
        val detached = detachedJws(payload)
        val token = issueToken()
        val assertion = JSONObject()
            .put("fingerprints", detached)
            .put("token", token)
            .toString()
        val envelope = JSONObject()
            .put("assertion", assertion)
            .put("idp", JSONObject().put("domain", DOMAIN).put("protocol", "default"))
            .toString()
        val value = base64(envelope.toByteArray(Charsets.UTF_8))
        val line = "a=identity:$value"

        val eol = if (sdp.contains("\r\n")) "\r\n" else "\n"
        val lines = sdp.split(Regex("\r\n|\n"))
            .filter { it.isNotEmpty() }
            .toMutableList()
        val mediaIndex = lines.indexOfFirst { it.startsWith("m=") }
        if (mediaIndex >= 0) {
            lines.add(mediaIndex, line)
        } else {
            lines.add(line)
        }
        return lines.joinToString(eol) + eol
    }

    private fun issueToken(): String {
        val publicKey = base64(currentKeyPair().public.encoded)
        val now = System.currentTimeMillis() / 1000L
        val header = JSONObject()
            .put("alg", "ES384")
            .put("x5u", publicKey)
            .toString()
        val claims = JSONObject()
            .put("exp", now + TOKEN_LIFETIME_SECONDS)
            .put("iat", now)
            .put("cpk", publicKey)
            .toString()
        return compactJws(header.toByteArray(Charsets.UTF_8), claims.toByteArray(Charsets.UTF_8))
    }

    private fun detachedJws(payload: ByteArray): String {
        val header = base64Url("{\"alg\":\"ES384\"}".toByteArray(Charsets.UTF_8))
        val body = base64Url(payload)
        val signingInput = "$header.$body"
        val signature = base64Url(sign(signingInput.toByteArray(Charsets.UTF_8)))
        return "$header..$signature"
    }

    private fun compactJws(header: ByteArray, payload: ByteArray): String {
        val head = base64Url(header)
        val body = base64Url(payload)
        val signingInput = "$head.$body"
        val signature = base64Url(sign(signingInput.toByteArray(Charsets.UTF_8)))
        return "$signingInput.$signature"
    }

    private fun sign(input: ByteArray): ByteArray {
        val signature = Signature.getInstance("SHA384withECDSA")
        signature.initSign(currentKeyPair().private)
        signature.update(input)
        val raw = signature.sign()
        // Android suele devolver DER; algunos proveedores ya devuelven r||s.
        return if (raw.size == P384_COMPONENT_SIZE * 2) raw else derToRaw(raw)
    }

    private fun extractFingerprints(sdp: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (rawLine in sdp.split("\r\n", "\n")) {
            val line = rawLine.trim()
            if (!line.startsWith("a=fingerprint:")) continue
            val fields = line.substring("a=fingerprint:".length)
                .split(' ')
                .filter { it.isNotBlank() }
            if (fields.size == 2) out.add(fields[0] to fields[1])
        }
        return out
    }

    private fun fingerprintPayload(fingerprints: List<Pair<String, String>>): ByteArray {
        // Debe ser el JSON canónico exacto que reconstruye el verificador:
        // sin espacios, con las claves en el orden algorithm,digest.
        val out = StringBuilder("{\"fingerprint\":[")
        fingerprints.forEachIndexed { index, (algorithm, digest) ->
            if (index > 0) out.append(',')
            out.append("{\"algorithm\":\"")
                .append(algorithm)
                .append("\",\"digest\":\"")
                .append(digest)
                .append("\"}")
        }
        out.append("]}")
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Convierte la firma DER de Java (`SEQUENCE { INTEGER r, INTEGER s }`) al
     * formato JOSE `r||s`, con cada componente rellenado a 48 bytes.
     */
    private fun derToRaw(der: ByteArray): ByteArray {
        var offset = 0

        fun readLength(): Int {
            var length = der[offset++].toInt() and 0xFF
            if (length and 0x80 != 0) {
                val count = length and 0x7F
                length = 0
                repeat(count) {
                    length = (length shl 8) or (der[offset++].toInt() and 0xFF)
                }
            }
            return length
        }

        fun readInteger(): ByteArray {
            require(der[offset++].toInt() == 0x02) { "DER: se esperaba INTEGER" }
            val length = readLength()
            val value = der.copyOfRange(offset, offset + length)
            offset += length
            return value
        }

        require(der[offset++].toInt() == 0x30) { "DER: se esperaba SEQUENCE" }
        readLength()
        val r = readInteger()
        val s = readInteger()
        val raw = ByteArray(P384_COMPONENT_SIZE * 2)
        copyComponent(r, raw, 0)
        copyComponent(s, raw, P384_COMPONENT_SIZE)
        return raw
    }

    private fun copyComponent(value: ByteArray, target: ByteArray, targetOffset: Int) {
        var start = 0
        while (start < value.size - 1 && value[start].toInt() == 0) start++
        val length = value.size - start
        require(length <= P384_COMPONENT_SIZE) { "componente ECDSA demasiado grande" }
        System.arraycopy(
            value,
            start,
            target,
            targetOffset + P384_COMPONENT_SIZE - length,
            length
        )
    }

    private fun base64(data: ByteArray): String =
        Base64.encodeToString(data, Base64.NO_WRAP)

    private fun base64Url(data: ByteArray): String =
        Base64.encodeToString(
            data,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
}
