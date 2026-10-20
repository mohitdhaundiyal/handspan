package app.handspan.core

import java.net.Socket
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/** This device's key pair and certificate. On Android the key lives in the AndroidKeyStore. */
class TlsIdentity(val certificate: X509Certificate, val privateKey: PrivateKey) {
    val deviceId: String = DeviceId.of(certificate.publicKey)
}

/** Device IDs are lowercase hex SHA-256 of the P-256 SubjectPublicKeyInfo (PROTOCOL §1). */
object DeviceId {
    val P256_SPKI_PREFIX: ByteArray = Hex.decode("3059301306072a8648ce3d020106082a8648ce3d030107034200")

    fun fromSpki(spki: ByteArray): String = Hex.encode(sha256(spki))

    fun of(key: PublicKey): String {
        val spki = key.encoded
        require(spki.size == 91 && spki.copyOfRange(0, 26).contentEquals(P256_SPKI_PREFIX)) { "not a P-256 key" }
        return fromSpki(spki)
    }
}

object Hex {
    fun encode(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun decode(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "odd hex length" }
        return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun isSha256(s: String?): Boolean = s != null && s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
}

fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

/**
 * Builds a self-signed P-256 certificate in software. The Android app uses the
 * certificate the AndroidKeyStore generates instead; this exists for tests and tools.
 */
object SelfSignedCert {
    fun generate(commonName: String = "Handspan ${UUID.randomUUID().toString().take(8)}"): TlsIdentity {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val pair = generator.generateKeyPair()
        val serial = ByteArray(16).also { SecureRandom().nextBytes(it) }
        serial[0] = ((serial[0].toInt() and 0x7F) or 0x01).toByte()
        val algorithm = Der.seq(Der.OID_ECDSA_SHA256)
        val name = Der.seq(Der.set(Der.seq(Der.OID_CN, Der.utf8(commonName))))
        val now = System.currentTimeMillis()
        val tbs = Der.seq(
            Der.tlv(0xA0, Der.integer(byteArrayOf(2))),
            Der.integer(serial),
            algorithm,
            name,
            Der.seq(Der.utcTime(Date(now - 86_400_000L)), Der.utcTime(Date(now + 3650L * 86_400_000L))),
            name,
            pair.public.encoded,
        )
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(tbs)
            sign()
        }
        val der = Der.seq(tbs, algorithm, Der.tlv(0x03, byteArrayOf(0) + signature))
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
        return TlsIdentity(cert, pair.private)
    }

    private object Der {
        val OID_ECDSA_SHA256 = Hex.decode("06082a8648ce3d040302")
        val OID_CN = Hex.decode("0603550403")

        fun tlv(tag: Int, content: ByteArray): ByteArray = byteArrayOf(tag.toByte()) + length(content.size) + content

        private fun length(n: Int): ByteArray {
            if (n < 0x80) return byteArrayOf(n.toByte())
            val bytes = generateSequence(n) { (it shr 8).takeIf { v -> v > 0 } }.map { (it and 0xFF).toByte() }.toList().reversed()
            return byteArrayOf((0x80 or bytes.size).toByte()) + bytes.toByteArray()
        }

        fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })
        fun set(vararg parts: ByteArray) = tlv(0x31, parts.fold(ByteArray(0)) { a, b -> a + b })
        fun utf8(s: String) = tlv(0x0C, s.toByteArray())

        fun integer(magnitude: ByteArray): ByteArray {
            var bytes = magnitude.dropWhile { it == 0.toByte() }.toByteArray()
            if (bytes.isEmpty()) bytes = byteArrayOf(0)
            if (bytes[0].toInt() and 0x80 != 0) bytes = byteArrayOf(0) + bytes
            return tlv(0x02, bytes)
        }

        fun utcTime(date: Date): ByteArray {
            val format = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            return tlv(0x17, format.format(date).toByteArray())
        }
    }
}

/** Mutual TLS 1.3 with the host pinned by device ID inside the handshake (PROTOCOL §4). */
object Tls {
    fun context(identity: TlsIdentity, expectedHostId: String): SSLContext =
        SSLContext.getInstance("TLSv1.3").apply {
            init(arrayOf(IdentityKeyManager(identity)), arrayOf(PinnedTrustManager(expectedHostId)), SecureRandom())
        }

    private class IdentityKeyManager(private val identity: TlsIdentity) : X509ExtendedKeyManager() {
        private val alias = "handspan"
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out java.security.Principal>?, socket: Socket?) = alias
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out java.security.Principal>?, engine: SSLEngine?) = alias
        override fun getClientAliases(keyType: String?, issuers: Array<out java.security.Principal>?) = arrayOf(alias)
        override fun getServerAliases(keyType: String?, issuers: Array<out java.security.Principal>?) = null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out java.security.Principal>?, socket: Socket?) = null
        override fun getCertificateChain(alias: String?) = arrayOf(identity.certificate)
        override fun getPrivateKey(alias: String?) = identity.privateKey
    }

    /** Accepts exactly one server key. No CA, hostname or validity checks: the pin is the trust. */
    private class PinnedTrustManager(private val expectedHostId: String) : X509ExtendedTrustManager() {
        private fun check(chain: Array<out X509Certificate>?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("no server certificate")
            val actual = try { DeviceId.of(leaf.publicKey) } catch (e: IllegalArgumentException) { throw CertificateException(e) }
            if (actual != expectedHostId) throw CertificateException("host key mismatch: expected ${expectedHostId.take(8)}, got ${actual.take(8)}")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("client role only")
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = throw CertificateException("client role only")
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = throw CertificateException("client role only")
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
