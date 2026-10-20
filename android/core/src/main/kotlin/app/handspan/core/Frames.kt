package app.handspan.core

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.UUID

/** Frame types from PROTOCOL §5. */
enum class FrameType(val code: Int) {
    HELLO(0x01), PING(0x02), PONG(0x03), PAIR_REQUEST(0x04), PAIR_RESULT(0x05),
    CLIP(0x10),
    OFFER(0x20), ACCEPT(0x21), DECLINE(0x22), CHUNK(0x23), FILE_DONE(0x24), VERIFIED(0x25), HASH_FAIL(0x26), CANCEL(0x27),
    UNPAIRED(0x30);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun of(code: Int): FrameType? = byCode[code]
    }
}

class Frame(val rawType: Int, val payload: ByteArray) {
    val type: FrameType? get() = FrameType.of(rawType)

    fun encoded(): ByteArray = ByteBuffer.allocate(5 + payload.size)
        .putInt(1 + payload.size)
        .put(rawType.toByte())
        .put(payload)
        .array()

    fun text(): String = payload.decodeToString()

    companion object {
        const val MAX_PAYLOAD = 8 * 1024 * 1024
        const val MAX_CHUNK_DATA = 256 * 1024

        fun empty(type: FrameType) = Frame(type.code, "{}".toByteArray())
    }
}

class ProtocolException(message: String) : IOException(message)

/** Reads whole frames from a stream; throws on oversize or truncated frames. */
class FrameReader(input: InputStream) {
    private val data = DataInputStream(input)

    /** Returns null on a clean end of stream between frames. */
    fun read(): Frame? {
        val length = try { data.readInt() } catch (_: EOFException) { return null }
        if (length < 1) throw ProtocolException("empty frame")
        if (length > 1 + Frame.MAX_PAYLOAD) throw ProtocolException("frame too large: $length")
        val type = data.readUnsignedByte()
        val payload = ByteArray(length - 1)
        data.readFully(payload)
        return Frame(type, payload)
    }
}

/** CHUNK payload: xferId(16) ‖ fileId(16) ‖ offset u64 BE ‖ data. */
class Chunk(val xferId: UUID, val fileId: UUID, val offset: Long, val data: ByteArray, val dataLength: Int = data.size) {
    fun frame(): Frame {
        val buffer = ByteBuffer.allocate(40 + dataLength)
        buffer.putLong(xferId.mostSignificantBits).putLong(xferId.leastSignificantBits)
        buffer.putLong(fileId.mostSignificantBits).putLong(fileId.leastSignificantBits)
        buffer.putLong(offset)
        buffer.put(data, 0, dataLength)
        return Frame(FrameType.CHUNK.code, buffer.array())
    }

    companion object {
        fun parse(frame: Frame): Chunk {
            val p = frame.payload
            if (p.size <= 40 || p.size - 40 > Frame.MAX_CHUNK_DATA) throw ProtocolException("chunk size")
            val b = ByteBuffer.wrap(p)
            val xfer = UUID(b.long, b.long)
            val file = UUID(b.long, b.long)
            val offset = b.long
            if (offset < 0) throw ProtocolException("chunk offset")
            return Chunk(xfer, file, offset, p.copyOfRange(40, p.size))
        }
    }
}
