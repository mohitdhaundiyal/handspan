package app.handspan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

class IncomingStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val content = ByteArray(300_000) { (it * 13).toByte() }
    private val item = OfferItem.file("../x.bin", content.size.toLong(), "application/octet-stream", Hex.encode(sha256(content)))
    private val offer = Offer(newWireId(), listOf(item))
    private val xferUuid = UUID.fromString(offer.xferId)
    private val fileUuid = UUID.fromString(item.fileId)

    @Test fun resumesFromPartLengthAfterRestart() {
        val parts = tmp.newFolder("parts")
        IncomingStore(parts).apply {
            accept(offer, listOf(item.fileId))
            write(Chunk(xferUuid, fileUuid, 0, content.copyOfRange(0, 100_000)))
            assertEquals(mapOf(item.fileId to 100_000L), offsets(offer.xferId))
        }
        // A new instance (app restart) still knows the transfer and its offset.
        val store = IncomingStore(parts)
        assertEquals(mapOf(item.fileId to 100_000L), store.offsets(offer.xferId))
        store.write(Chunk(xferUuid, fileUuid, 100_000, content.copyOfRange(100_000, content.size)))
        val (part, it) = store.prepareFinish(offer.xferId, item.fileId)!!
        assertTrue(IncomingStore.verify(part, it))
        val saved = DirectorySink(tmp.newFolder("out")).commit(part, FileNames.sanitize(it.name!!), it.mime)
        assertEquals("x.bin", saved.displayName)
        store.markVerified(offer.xferId, item.fileId)
        assertTrue(store.isComplete(offer.xferId))
    }

    @Test(expected = ProtocolException::class)
    fun rejectsOutOfOrderChunk() {
        IncomingStore(tmp.newFolder()).apply {
            accept(offer, listOf(item.fileId))
            write(Chunk(xferUuid, fileUuid, 5, byteArrayOf(1)))
        }
    }

    @Test fun detectsCorruption() {
        val store = IncomingStore(tmp.newFolder())
        store.accept(offer, listOf(item.fileId))
        store.write(Chunk(xferUuid, fileUuid, 0, ByteArray(content.size)))
        val (part, it) = store.prepareFinish(offer.xferId, item.fileId)!!
        assertFalse(IncomingStore.verify(part, it))
    }
}
