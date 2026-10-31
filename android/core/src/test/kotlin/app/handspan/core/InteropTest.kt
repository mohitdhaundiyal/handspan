package app.handspan.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Drives the real Swift host (macos/ handspan-host) with the Kotlin PhoneClient.
 * Run via scripts/interop.sh, which builds the host and sets HANDSPAN_HOST_BIN.
 */
class InteropTest {
    @get:Rule val tmp = TemporaryFolder()

    private val hosts = mutableListOf<HostProcess>()
    private lateinit var host: HostProcess
    private val phoneEvents = LinkedBlockingQueue<Pair<String, Any>>()
    private lateinit var client: PhoneClient

    private val listener = object : PhoneListener {
        override fun onStatus(status: PhoneClient.Status) { phoneEvents += "status" to status }
        override fun onPaired(hostName: String, code: String) { phoneEvents += "paired" to code }
        override fun onPairingFailed(reason: String) { phoneEvents += "pairFailed" to reason }
        override fun onClipboard(text: String) { phoneEvents += "clip" to text }
        override fun onItems(items: List<OfferItem>) { phoneEvents += "items" to items }
        override fun onOffer(offer: Offer, decide: (Boolean) -> Unit) { phoneEvents += "offer" to offer; decide(true) }
        override fun onFileReceived(file: ReceivedFile) { phoneEvents += "file" to file }
        override fun onHosts(hosts: List<HostPairing>, connectedHostId: String?) { phoneEvents += "connectedTo" to (connectedHostId ?: "") }
        override fun log(message: String) { println("phone: $message") }
    }

    /** One handspan-host process: a Mac, or one account of a Mac. */
    private class HostProcess(bin: String, private val label: String) {
        private val process: Process = ProcessBuilder(bin).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        private val events = LinkedBlockingQueue<JsonObject>()
        @Volatile var id: String? = null
        /** The port it listens on now; null while suspended. */
        @Volatile var port: Int? = null

        init {
            Thread {
                process.inputStream.bufferedReader().forEachLine { line ->
                    println("$label: $line")
                    val event = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@forEachLine
                    when (event["event"]?.jsonPrimitive?.content) {
                        "ready" -> id = event.getValue("id").jsonPrimitive.content
                        "listening" -> port = event.getValue("port").jsonPrimitive.content.toInt()
                    }
                    events += event
                }
            }.apply { isDaemon = true }.start()
        }

        val address: String? get() = port?.let { "127.0.0.1:$it" }

        fun command(line: String) {
            if (line == "suspend") port = null
            process.outputStream.write("$line\n".toByteArray())
            process.outputStream.flush()
        }

        fun event(name: String, timeoutSec: Long = 20): JsonObject {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSec)
            while (System.nanoTime() < deadline) {
                val e = events.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (e["event"]?.jsonPrimitive?.content == name) return e
            }
            throw AssertionError("$label never emitted '$name'")
        }

        fun stop() {
            runCatching { command("quit") }
            process.waitFor(5, TimeUnit.SECONDS)
            process.destroyForcibly()
        }
    }

    private fun startHost(label: String): HostProcess {
        val bin = System.getProperty("handspan.hostBin")
        assumeTrue("set HANDSPAN_HOST_BIN (scripts/interop.sh) to run", bin != null)
        return HostProcess(bin, label).also { hosts += it }
    }

    @Before fun startFirstHost() {
        host = startHost("host")
    }

    @After fun stopAll() {
        if (::client.isInitialized) client.stop()
        hosts.forEach(HostProcess::stop)
    }

    private fun command(line: String) = host.command(line)

    private fun hostEvent(name: String, timeoutSec: Long = 20) = host.event(name, timeoutSec)

    @Suppress("UNCHECKED_CAST")
    private fun <T> phoneEvent(name: String, timeoutSec: Long = 20): T {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSec)
        while (System.nanoTime() < deadline) {
            val (n, value) = phoneEvents.poll(100, TimeUnit.MILLISECONDS) ?: continue
            if (n == "pairFailed") throw AssertionError("pairing failed: $value")
            if (n == name) return value as T
        }
        throw AssertionError("phone never emitted '$name'")
    }

    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content

    /** Blocks the upload after [gateAt] bytes until released; records each open's skip offset. */
    private class GatedSource(val bytes: ByteArray, val gateAt: Int) : FileSource {
        override val name = "photo.jpg"
        override val size = bytes.size.toLong()
        override val mime = "image/jpeg"
        val gate = CountDownLatch(1)
        val reachedGate = CountDownLatch(1)
        val skips = mutableListOf<Long>()
        var opens = 0

        override fun open(): InputStream {
            val index = opens++
            return object : InputStream() {
                var pos = 0
                var skipped = 0L
                override fun read(): Int = throw UnsupportedOperationException()
                override fun skip(n: Long): Long {
                    val k = minOf(n, (bytes.size - pos).toLong())
                    pos += k.toInt(); skipped += k
                    return k
                }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (pos >= bytes.size) return -1
                    // open #0 is hashing, #1 the first upload attempt: stop it at the gate.
                    if (index == 1 && pos >= gateAt) {
                        reachedGate.countDown()
                        gate.await()
                        return -1
                    }
                    val limit = if (index == 1) minOf(len, gateAt - pos) else len
                    val n = minOf(limit, bytes.size - pos)
                    System.arraycopy(bytes, pos, b, off, n)
                    pos += n
                    return n
                }
                override fun close() { synchronized(skips) { skips += skipped } }
            }
        }
    }

    private class MemoryStore(@Volatile var value: List<HostPairing> = emptyList()) : PairingStore {
        override fun load() = value
        override fun save(hosts: List<HostPairing>) { value = hosts }
    }

    private fun waitForStatus(status: PhoneClient.Status) {
        while (true) {
            val (name, value) = phoneEvents.poll(20, TimeUnit.SECONDS) ?: throw AssertionError("never reached $status")
            if (name == "status" && value == status) return
        }
    }

    private fun waitForConnection(to: HostProcess) {
        while (true) {
            val (name, value) = phoneEvents.poll(30, TimeUnit.SECONDS) ?: throw AssertionError("never connected to ${to.id}")
            if (name == "pairFailed") throw AssertionError("pairing failed: $value")
            if (name == "connectedTo" && value == to.id) return
        }
    }

    private fun pairWith(h: HostProcess) {
        h.command("pair")
        client.pair(PairingInvite.parse(h.event("pairing").getValue("uri").jsonPrimitive.content)!!)
        phoneEvent<String>("paired")
        h.event("paired")
    }

    /**
     * The saved address has gone away (e.g. a temporary IPv6 address rotated after a
     * router restart). The phone must still find the host: first from its remembered
     * IPv4 address, then, with nothing remembered, from discovery alone.
     */
    @Test fun reconnectsWhenLastAddressIsGone() {
        val port = hostEvent("ready").getValue("port").jsonPrimitive.content
        command("pair")
        val invite = PairingInvite.parse(hostEvent("pairing").str("uri"))!!
        val identity = SelfSignedCert.generate()
        val store = MemoryStore()
        client = PhoneClient(identity, "Interop Phone", store, tmp.newFolder("parts"), DirectorySink(tmp.newFolder("dl")), listener)
        client.start()
        client.pair(invite)
        phoneEvent<String>("paired")
        client.stop()

        val dead = "[2001:db8::7a6c]:$port"
        val good = "127.0.0.1:$port"
        val paired = store.value.single()
        phoneEvents.clear()
        val remembered = MemoryStore(listOf(paired.copy(lastAddress = dead, addresses = listOf(dead, good))))
        client = PhoneClient(identity, "Interop Phone", remembered, tmp.newFolder("parts2"), DirectorySink(tmp.newFolder("dl2")), listener)
        client.start()
        waitForStatus(PhoneClient.Status.CONNECTED)
        assertEquals(good, remembered.value.single().lastAddress)
        client.stop()

        phoneEvents.clear()
        val discoveredOnly = MemoryStore(listOf(paired.copy(lastAddress = dead, addresses = emptyList())))
        client = PhoneClient(identity, "Interop Phone", discoveredOnly, tmp.newFolder("parts3"), DirectorySink(tmp.newFolder("dl3")), listener,
            discoveredAddresses = { listOf(good) })
        client.start()
        waitForStatus(PhoneClient.Status.CONNECTED)
    }

    @Test fun phoneAndMacInteroperate() {
        hostEvent("ready")
        command("pair")
        val invite = PairingInvite.parse(hostEvent("pairing").str("uri"))!!

        val store = MemoryStore()
        val received = tmp.newFolder("phone-downloads")
        client = PhoneClient(SelfSignedCert.generate(), "Interop Phone", store, tmp.newFolder("parts"), DirectorySink(received), listener)
        client.start()
        client.pair(invite)

        // Both implementations derive the same confirmation code.
        val phoneCode: String = phoneEvent("paired")
        assertEquals(phoneCode, hostEvent("paired").str("code"))
        assertEquals(invite.hostId, store.value.single().hostId)

        // Clipboard both ways.
        client.localClipboardChanged("héllo from kotlin 👋")
        assertEquals("héllo from kotlin 👋", hostEvent("clip").str("text"))
        command("clip hello from swift")
        assertEquals("hello from swift", phoneEvent<String>("clip"))

        // Links.
        client.sendText("https://example.com/handspan")
        val item = hostEvent("item")
        assertEquals("url" to "https://example.com/handspan", item.str("kind") to item.str("text"))
        command("text https://example.org/back")
        val items: List<OfferItem> = phoneEvent("items")
        assertEquals(ItemKind.URL, items.single().kind)

        // Phone → Mac file, interrupted mid-way and resumed after reconnecting.
        val bytes = Random(42).nextBytes(1_500_000)
        val source = GatedSource(bytes, gateAt = 600_000)
        client.sendFiles(listOf(source))
        assertTrue(source.reachedGate.await(20, TimeUnit.SECONDS))
        Thread.sleep(300) // let the first 600 kB reach the host's part file
        client.dropConnection()
        source.gate.countDown()
        val file = hostEvent("file", timeoutSec = 30)
        assertEquals(Hex.encode(sha256(bytes)), file.str("sha256"))
        assertArrayEquals(bytes, File(file.str("path")).readBytes())
        val resumedFrom = synchronized(source.skips) { source.skips.last() }
        assertTrue("upload should resume past the first chunks, resumed at $resumedFrom", resumedFrom >= 256 * 1024)

        // Mac → phone file.
        val outgoing = tmp.newFile("from-mac.bin").apply { writeBytes(Random(7).nextBytes(900_000)) }
        command("send ${outgoing.absolutePath}")
        val got: ReceivedFile = phoneEvent("file", timeoutSec = 30)
        assertArrayEquals(outgoing.readBytes(), File(got.location).readBytes())
        assertEquals("from-mac.bin", got.displayName)
    }

    /**
     * Two accounts on one Mac, each running Handspan: only the account on screen listens
     * (the other is suspended). The phone pairs with both once, then follows account
     * switches in both directions without pairing again.
     */
    @Test fun followsTheActiveMacAccount() {
        val first = host.also { it.event("ready") }
        val second = startHost("second").also { it.event("ready") }
        val store = MemoryStore()
        client = PhoneClient(
            SelfSignedCert.generate(), "Interop Phone", store, tmp.newFolder("parts"), DirectorySink(tmp.newFolder("dl")), listener,
            discoveredAddresses = { id -> hosts.filter { it.id == id }.mapNotNull { it.address } },
        )
        client.start()

        pairWith(first)
        first.command("suspend")
        pairWith(second)
        assertEquals(listOf(second.id, first.id), store.value.map { it.hostId })

        var switches = 0
        fun switchTo(active: HostProcess, inactive: HostProcess) {
            switches++
            inactive.command("suspend")
            active.command("resume")
            active.event("listening")
            client.wake()
            waitForConnection(active)
            // Unique texts: both sides ignore a clip they have just sent or applied.
            active.command("clip mac $switches")
            assertEquals("mac $switches", phoneEvent<String>("clip"))
            client.localClipboardChanged("phone $switches")
            assertEquals("phone $switches", active.event("clip").str("text"))
        }

        switchTo(first, second)
        switchTo(second, first)
        switchTo(first, second)
        assertEquals(setOf(first.id, second.id), store.value.map { it.hostId }.toSet())
        assertEquals(first.id, store.value.first().hostId)
    }
}
