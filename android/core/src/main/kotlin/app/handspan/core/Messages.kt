package app.handspan.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.util.UUID

// JSON payloads from PROTOCOL §5. UUIDs travel as lowercase strings.

val WireJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

inline fun <reified T> Frame.decode(): T = WireJson.decodeFromString(text())
inline fun <reified T> frameOf(type: FrameType, message: T) = Frame(type.code, WireJson.encodeToString(message).toByteArray())

@Serializable data class Hello(val v: Int, val id: String, val name: String, val platform: String, val paired: Boolean? = null)
@Serializable data class PairRequest(val name: String, val proof: String)
@Serializable data class PairResult(val ok: Boolean, val name: String? = null, val reason: String? = null)

@Serializable
data class Clip(val id: String, val origin: String, val mime: String = "text/plain", val text: String, val sha256: String) {
    val isIntact: Boolean get() = Hex.encode(sha256(text.toByteArray())) == sha256.lowercase()

    companion object {
        fun of(origin: String, text: String) =
            Clip(id = newWireId(), origin = origin, text = text, sha256 = Hex.encode(sha256(text.toByteArray())))
    }
}

@Serializable
enum class ItemKind {
    @SerialName("file") FILE,
    @SerialName("text") TEXT,
    @SerialName("url") URL,
}

@Serializable
data class OfferItem(
    val fileId: String,
    val kind: ItemKind,
    val name: String? = null,
    val size: Long? = null,
    val mime: String? = null,
    val sha256: String? = null,
    val text: String? = null,
) {
    companion object {
        fun file(name: String, size: Long, mime: String, sha256: String) =
            OfferItem(newWireId(), ItemKind.FILE, name = name, size = size, mime = mime, sha256 = sha256)

        fun text(text: String): OfferItem {
            val trimmed = text.trim()
            val isUrl = runCatching { URI(trimmed) }.getOrNull()?.let { it.scheme?.lowercase() in setOf("http", "https") && it.host != null } ?: false
            return OfferItem(newWireId(), if (isUrl) ItemKind.URL else ItemKind.TEXT, text = text)
        }
    }
}

@Serializable
data class Offer(val xferId: String, val items: List<OfferItem>) {
    val files: List<OfferItem> get() = items.filter { it.kind == ItemKind.FILE }
}

@Serializable data class Accept(val xferId: String, val files: Map<String, Long>)
@Serializable data class TransferRef(val xferId: String)
@Serializable data class FileRef(val xferId: String, val fileId: String)
@Serializable data class Cancel(val xferId: String, val reason: String)

fun newWireId(): String = UUID.randomUUID().toString()

fun isWireUuid(s: String?): Boolean = s != null && runCatching { UUID.fromString(s).toString() == s }.getOrDefault(false)
