package app.handspan.android

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.handspan.core.FileSource
import app.handspan.core.HostPairing
import app.handspan.core.PairingStore
import app.handspan.core.ReceivedFile
import app.handspan.core.ReceivedFileSink
import app.handspan.core.TlsIdentity
import app.handspan.core.WireJson
import java.io.File
import java.io.InputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.security.auth.x500.X500Principal

/** The device's P-256 key, generated inside the AndroidKeyStore and never exportable. */
object KeystoreIdentity {
    private const val ALIAS = "handspan.identity"

    fun loadOrCreate(): TlsIdentity {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry)?.let {
            return TlsIdentity(it.certificate as X509Certificate, it.privateKey)
        }
        val now = System.currentTimeMillis()
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            // The TLS stack may hand the key an already-computed SHA-256 digest to sign,
            // which the keystore only allows when DIGEST_NONE is permitted.
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE)
            .setCertificateSubject(X500Principal("CN=Handspan ${Build.MODEL}"))
            .setCertificateSerialNumber(BigInteger(127, SecureRandom()).add(BigInteger.ONE))
            .setCertificateNotBefore(Date(now - 86_400_000L))
            .setCertificateNotAfter(Date(now + 3650L * 86_400_000L))
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(spec)
            generateKeyPair()
        }
        val entry = keyStore.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
        return TlsIdentity(entry.certificate as X509Certificate, entry.privateKey as PrivateKey)
    }

    fun delete() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS)
    }
}

class PrefsPairingStore(context: Context) : PairingStore {
    private val prefs = context.getSharedPreferences("pairing", Context.MODE_PRIVATE)

    override fun load(): HostPairing? =
        prefs.getString("host", null)?.let { runCatching { WireJson.decodeFromString<HostPairing>(it) }.getOrNull() }

    override fun save(pairing: HostPairing?) {
        prefs.edit().apply {
            if (pairing == null) remove("host") else putString("host", WireJson.encodeToString(pairing))
        }.apply()
    }
}

/** Saves verified files to Download/Handspan via MediaStore (no storage permission needed). */
class MediaStoreSink(private val context: Context) : ReceivedFileSink {
    override fun commit(part: File, name: String, mime: String?): ReceivedFile {
        val resolver = context.contentResolver
        val type = mime?.takeIf { it.contains('/') } ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, type)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Handspan")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore refused $name")
        try {
            resolver.openOutputStream(uri)!!.use { out -> part.inputStream().use { it.copyTo(out, 1 shl 16) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        part.delete()
        val displayName = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: name
        return ReceivedFile(displayName, uri.toString(), type)
    }
}

/** A shared or picked content URI. */
class UriFileSource(private val context: Context, private val uri: Uri) : FileSource {
    override val name: String
    override val size: Long
    override val mime: String = context.contentResolver.getType(uri) ?: "application/octet-stream"

    init {
        var n: String? = null
        var s = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) {
                n = it.getString(0)
                if (!it.isNull(1)) s = it.getLong(1)
            }
        }
        name = n ?: uri.lastPathSegment ?: "file"
        size = if (s >= 0) s else context.contentResolver.openInputStream(uri)?.use { stream -> stream.skip(Long.MAX_VALUE) } ?: 0
    }

    override fun open(): InputStream = context.contentResolver.openInputStream(uri) ?: error("cannot open $uri")
}

fun deviceName(context: Context): String =
    Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL
