package app.handspan.core

import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

/** Something the phone can send: a file, or an Android content URI. */
interface FileSource {
    val name: String
    val size: Long
    val mime: String
    fun open(): InputStream
}

class LocalFileSource(private val file: File, override val mime: String = "application/octet-stream") : FileSource {
    override val name: String get() = file.name
    override val size: Long get() = file.length()
    override fun open(): InputStream = file.inputStream()
}

/** A verified file handed to the platform (e.g. MediaStore Downloads). */
data class ReceivedFile(val displayName: String, val location: String, val mime: String?)

fun interface ReceivedFileSink {
    /** Moves or copies the verified part file to its final place. May block. */
    fun commit(part: File, name: String, mime: String?): ReceivedFile
}

/** Saves into a directory with " (n)" de-duplication; used on the JVM and in tests. */
class DirectorySink(private val dir: File) : ReceivedFileSink {
    override fun commit(part: File, name: String, mime: String?): ReceivedFile {
        dir.mkdirs()
        val target = File(dir, FileNames.unique(name) { File(dir, it).exists() })
        if (!part.renameTo(target)) {
            part.copyTo(target)
            part.delete()
        }
        return ReceivedFile(target.name, target.absolutePath, mime)
    }
}

data class TransferProgress(
    val id: String,
    val direction: Direction,
    val title: String,
    val bytesDone: Long,
    val bytesTotal: Long,
    val status: Status,
    val error: String? = null,
) {
    enum class Direction { OUTGOING, INCOMING }
    enum class Status { WAITING, ACTIVE, DONE, FAILED }
}

fun sha256Hex(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    input.use {
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val n = it.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return Hex.encode(digest.digest())
}

/** A file offer we are sending; kept in memory so it resumes after a reconnect. */
internal class OutgoingTransfer(val offer: Offer, val sources: Map<String, FileSource>) {
    enum class State { AWAITING_ANSWER, SENDING, DONE, CANCELLED }

    @Volatile var state = State.AWAITING_ANSWER
    /** Bumped whenever streaming must stop (reconnect, cancel) so stale upload threads exit. */
    @Volatile var generation = 0
    val queue = ArrayDeque<Pair<String, Long>>()
    val verified = mutableSetOf<String>()
    val retried = mutableSetOf<String>()
    var offeredAt = System.currentTimeMillis()

    val totalBytes: Long get() = offer.files.sumOf { it.size ?: 0 }
    val verifiedBytes: Long get() = offer.files.filter { it.fileId in verified }.sumOf { it.size ?: 0 }
    val title: String get() = offer.files.singleOrNull()?.name ?: "${offer.files.size} files"
}

/**
 * Receiver-side state: one directory per transfer under [partsDir] holding
 * `state.json` and `<fileId>.part`, so an accepted transfer resumes without
 * asking again (PROTOCOL §8). Only touched from the client's core thread,
 * except [verify], which only reads.
 */
internal class IncomingStore(private val partsDir: File) {
    @Serializable
    data class State(val offer: Offer, val accepted: List<String>, val verified: List<String>, val createdAt: Long)

    private val states = mutableMapOf<String, State>()
    private val open = mutableMapOf<String, Pair<FileOutputStream, Long>>()

    init {
        partsDir.mkdirs()
        partsDir.listFiles()?.forEach { dir ->
            val state = runCatching { WireJson.decodeFromString<State>(File(dir, "state.json").readText()) }.getOrNull()
            if (state == null || state.offer.xferId != dir.name) dir.deleteRecursively() else states[dir.name] = state
        }
    }

    fun state(xferId: String) = states[xferId]

    fun accept(offer: Offer, files: List<String>) {
        val state = State(offer, files, emptyList(), System.currentTimeMillis())
        dir(offer.xferId).mkdirs()
        states[offer.xferId] = state
        save(state)
    }

    fun offsets(xferId: String): Map<String, Long> {
        val state = states[xferId] ?: return emptyMap()
        return state.accepted.filter { it !in state.verified }.associateWith {
            closeHandle(xferId, it)
            part(xferId, it).length()
        }
    }

    /** Appends a chunk; throws if it is not for an accepted file or not the next expected byte. */
    fun write(chunk: Chunk) {
        val xferId = chunk.xferId.toString()
        val fileId = chunk.fileId.toString()
        val state = states[xferId] ?: throw ProtocolException("unknown transfer")
        if (fileId !in state.accepted || fileId in state.verified) throw ProtocolException("unknown file")
        val key = "$xferId/$fileId"
        val (stream, size) = open[key] ?: part(xferId, fileId).let { FileOutputStream(it, true) to it.length() }
        if (chunk.offset != size) {
            open[key] = stream to size
            throw ProtocolException("unexpected offset ${chunk.offset}, expected $size")
        }
        stream.write(chunk.data, 0, chunk.dataLength)
        open[key] = stream to size + chunk.dataLength
    }

    fun receivedBytes(xferId: String): Long {
        val state = states[xferId] ?: return 0
        return state.offer.files.filter { it.fileId in state.accepted }.sumOf {
            when {
                it.fileId in state.verified -> it.size ?: 0
                else -> open["$xferId/${it.fileId}"]?.second ?: 0
            }
        }
    }

    fun totalBytes(xferId: String): Long {
        val state = states[xferId] ?: return 0
        return state.offer.files.filter { it.fileId in state.accepted }.sumOf { it.size ?: 0 }
    }

    /** Closes the part file and returns it with the item, ready for [verify]. */
    fun prepareFinish(xferId: String, fileId: String): Pair<File, OfferItem>? {
        val state = states[xferId] ?: return null
        if (fileId !in state.accepted) return null
        val item = state.offer.items.firstOrNull { it.fileId == fileId } ?: return null
        closeHandle(xferId, fileId)
        return part(xferId, fileId).also { if (!it.exists()) it.createNewFile() } to item
    }

    fun markVerified(xferId: String, fileId: String) {
        val state = states[xferId] ?: return
        val updated = state.copy(verified = state.verified + fileId)
        if (updated.verified.toSet() == updated.accepted.toSet()) remove(xferId) else {
            states[xferId] = updated
            save(updated)
        }
    }

    fun isComplete(xferId: String) = xferId !in states

    fun remove(xferId: String) {
        open.keys.filter { it.startsWith("$xferId/") }.forEach { runCatching { open.remove(it)?.first?.close() } }
        states.remove(xferId)
        dir(xferId).deleteRecursively()
    }

    fun purge(olderThanMillis: Long) {
        val cutoff = System.currentTimeMillis() - olderThanMillis
        states.filterValues { it.createdAt < cutoff }.keys.toList().forEach(::remove)
    }

    private fun closeHandle(xferId: String, fileId: String) {
        runCatching { open.remove("$xferId/$fileId")?.first?.close() }
    }

    private fun dir(xferId: String) = File(partsDir, xferId)
    private fun part(xferId: String, fileId: String) = File(dir(xferId), "$fileId.part")
    private fun save(state: State) {
        val target = File(dir(state.offer.xferId), "state.json")
        val tmp = File(target.parentFile, "state.json.tmp")
        tmp.writeText(WireJson.encodeToString(state))
        tmp.renameTo(target)
    }

    companion object {
        /** Size and SHA-256 check of a finished part file (PROTOCOL §8 step 4). */
        fun verify(part: File, item: OfferItem): Boolean =
            part.length() == item.size && sha256Hex(part.inputStream()) == item.sha256?.lowercase()
    }
}
