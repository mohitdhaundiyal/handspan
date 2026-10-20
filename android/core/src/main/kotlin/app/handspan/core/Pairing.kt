package app.handspan.core

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The QR payload and the proof/confirmation math from PROTOCOL §3 and §6. */
data class PairingInvite(val hostId: String, val secret: ByteArray, val hosts: List<String>, val name: String) {
    val uri: String
        get() = "handspan://pair?v=1&id=$hostId&k=${Base64.getUrlEncoder().withoutPadding().encodeToString(secret)}" +
            "&h=${hosts.joinToString(",") { it.replace("[", "%5B").replace("]", "%5D") }}&n=${encode(name)}"

    override fun equals(other: Any?) = other is PairingInvite && hostId == other.hostId &&
        secret.contentEquals(other.secret) && hosts == other.hosts && name == other.name

    override fun hashCode() = hostId.hashCode()

    companion object {
        fun parse(uri: String): PairingInvite? {
            val parsed = runCatching { URI(uri) }.getOrNull() ?: return null
            if (parsed.scheme != "handspan" || parsed.host != "pair") return null
            val query = (parsed.rawQuery ?: "").split("&").filter { it.isNotEmpty() }.associate {
                val (k, v) = if ("=" in it) it.split("=", limit = 2) else listOf(it, "")
                decode(k) to decode(v)
            }
            if (query["v"] != "1") return null
            val id = query["id"]?.takeIf { it.length == 64 && Hex.isSha256(it) }?.lowercase() ?: return null
            val secret = query["k"]?.let { runCatching { Base64.getUrlDecoder().decode(it) }.getOrNull() }?.takeIf { it.size == 32 } ?: return null
            val hosts = query["h"].orEmpty().split(",").filter { it.isNotBlank() }
            return PairingInvite(id, secret, hosts, query["n"].orEmpty())
        }

        fun proof(secret: ByteArray, hostId: String, phoneId: String): String =
            Hex.encode(mac(secret, "handspan-pair-v1", hostId, phoneId))

        fun confirmCode(secret: ByteArray, hostId: String, phoneId: String): String {
            val digest = mac(secret, "handspan-confirm-v1", hostId, phoneId)
            val value = ByteBuffer.wrap(digest, 0, 4).int.toLong() and 0xFFFFFFFFL
            return "%06d".format(value % 1_000_000)
        }

        fun constantTimeEquals(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

        private fun mac(secret: ByteArray, label: String, hostId: String, phoneId: String): ByteArray =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(secret, "HmacSHA256"))
                update(label.toByteArray())
                update(Hex.decode(hostId))
                update(Hex.decode(phoneId))
                doFinal()
            }

        private fun decode(s: String) = URLDecoder.decode(s.replace("+", "%2B"), Charsets.UTF_8)
        private fun encode(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
    }
}

/** Splits "host:port" or "[v6]:port". */
fun parseHostPort(s: String): Pair<String, Int>? {
    val (host, port) = if (s.startsWith("[")) {
        val end = s.indexOf("]:").takeIf { it > 0 } ?: return null
        s.substring(1, end) to s.substring(end + 2)
    } else {
        val colon = s.lastIndexOf(':').takeIf { it > 0 } ?: return null
        s.substring(0, colon) to s.substring(colon + 1)
    }
    return port.toIntOrNull()?.takeIf { it in 1..65535 }?.let { host to it }
}

/** Receiver-side file name rules from PROTOCOL §8. */
object FileNames {
    fun sanitize(name: String): String {
        var s = name.filter { it.code >= 0x20 && it.code != 0x7F }
        s = s.replace('\\', '/').substringAfterLast('/')
        s = s.replace(':', '_').trim().trimStart('.')
        s = truncate(s, 200)
        return s.ifEmpty { "file" }
    }

    /** "name.ext", or "name (1).ext", "name (2).ext"… for the first `taken(...) == false`. */
    fun unique(name: String, taken: (String) -> Boolean): String {
        if (!taken(name)) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 }
        val base = dot?.let { name.substring(0, it) } ?: name
        val ext = dot?.let { name.substring(it) } ?: ""
        return generateSequence(1) { it + 1 }.map { "$base ($it)$ext" }.first { !taken(it) }
    }

    private fun truncate(s: String, maxBytes: Int): String {
        if (s.toByteArray().size <= maxBytes) return s
        val dot = s.lastIndexOf('.')
        val ext = if (dot > 0 && s.length - dot <= 17) s.substring(dot) else ""
        var base = s.substring(0, s.length - ext.length)
        while ((base + ext).toByteArray().size > maxBytes) base = base.dropLast(1)
        return base + ext
    }
}
