package app.handspan.core

import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket

/**
 * A Mac this phone is paired with, plus every address it has been reached at
 * (most recent first) so a reconnect still works when mDNS does not.
 */
@Serializable
data class HostPairing(
    val hostId: String,
    val hostName: String,
    val lastAddress: String? = null,
    val addresses: List<String> = emptyList(),
) {
    /** Addresses to try, IPv4 first: a DHCP lease outlives a temporary IPv6 address. */
    val knownAddresses: List<String>
        get() = (listOfNotNull(lastAddress) + addresses).distinct().sortedBy { it.startsWith("[") }

    fun remember(address: String) = copy(
        lastAddress = address,
        addresses = (listOf(address) + addresses).distinct().take(MAX_ADDRESSES),
    )

    companion object {
        private const val MAX_ADDRESSES = 8
    }
}

/** The paired Macs, most recently connected first. */
interface PairingStore {
    fun load(): List<HostPairing>
    fun save(hosts: List<HostPairing>)
}

/** Callbacks run on the client's core thread; platform code should hop to its UI thread. */
interface PhoneListener {
    fun onStatus(status: PhoneClient.Status) {}
    fun onPaired(hostName: String, code: String) {}
    fun onPairingFailed(reason: String) {}
    fun onUnpaired(hostName: String) {}
    /** The paired Macs changed, or the phone connected to or disconnected from one. */
    fun onHosts(hosts: List<HostPairing>, connectedHostId: String?) {}
    fun onClipboard(text: String) {}
    fun onItems(items: List<OfferItem>) {}
    /** Called for offers containing files. Call [decide] exactly once, from any thread. */
    fun onOffer(offer: Offer, decide: (Boolean) -> Unit) { decide(false) }
    fun onFileReceived(file: ReceivedFile) {}
    fun onTransfers(transfers: List<TransferProgress>) {}
    fun log(message: String) {}
}

/**
 * The phone side of Handspan (PROTOCOL §4): dials a paired Mac, keeps the
 * connection alive, syncs the clipboard and moves files in both directions.
 *
 * The phone can be paired with several Macs (or macOS accounts) but is connected
 * to one at a time: whichever paired Mac is reachable, preferring the ones
 * discovery currently sees, then the most recently connected.
 * All mutable state is confined to the single [core] thread.
 */
class PhoneClient(
    private val identity: TlsIdentity,
    private val deviceName: String,
    private val pairingStore: PairingStore,
    partsDir: File,
    private val sink: ReceivedFileSink,
    private val listener: PhoneListener,
    /** Addresses discovery currently sees for a paired host ID, freshest first. */
    private val discoveredAddresses: (hostId: String) -> List<String> = { emptyList() },
) {
    enum class Status { UNPAIRED, OFFLINE, CONNECTING, CONNECTED }

    val deviceId: String get() = identity.deviceId

    private val core: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { Thread(it, "handspan-core").apply { isDaemon = true } }
    private val io = Executors.newCachedThreadPool { Thread(it, "handspan-io").apply { isDaemon = true } }
    private val running = AtomicBoolean(false)
    private val nudge = java.util.concurrent.Semaphore(0)

    private var hosts: List<HostPairing> = pairingStore.load()
    private var link: Link? = null
    private var connectedHostId: String? = null
    private var status = Status.OFFLINE
    private val incoming = IncomingStore(partsDir)
    private val outgoing = linkedMapOf<String, OutgoingTransfer>()
    private val progress = linkedMapOf<String, TransferProgress>()
    private val pendingDecisions = mutableSetOf<String>()
    private val deliveredItems = mutableSetOf<String>()
    private var lastAppliedClip: String? = null
    private var lastSentClip: String? = null
    private var syncEnabled = true
    private var lastProgressPush = 0L


    fun start() {
        if (!running.compareAndSet(false, true)) return
        post {
            incoming.purge(24 * 3600_000L)
            setStatus(if (hosts.isEmpty()) Status.UNPAIRED else Status.OFFLINE)
            pushHosts()
        }
        core.scheduleWithFixedDelay(::tick, PING_INTERVAL_MS, PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
        Thread(::connectLoop, "handspan-connector").apply { isDaemon = true }.start()
    }

    fun stop() {
        running.set(false)
        wake()
        post { link?.close() }
        core.shutdown()
        io.shutdownNow()
    }

    /** Runs on the core thread; silently dropped after [stop]. */
    private fun post(block: () -> Unit) {
        try {
            core.execute(block)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }

    /** Retry now: the network changed or discovery saw a paired host. */
    fun wake() = nudge.release()


    /**
     * Pairs with the host in [invite] and connects to it, keeping any other paired Macs.
     * The result arrives via [PhoneListener.onPaired] / [PhoneListener.onPairingFailed].
     */
    fun pair(invite: PairingInvite, extraAddresses: List<String> = emptyList()) {
        io.execute {
            val candidates = (invite.hosts + extraAddresses).distinct()
            var lastError = "No address in the QR code was reachable"
            for (address in candidates) {
                val (host, port) = parseHostPort(address) ?: continue
                try {
                    val l = Link.open(host, port, identity, invite.hostId)
                    try {
                        l.write(frameOf(FrameType.HELLO, Hello(1, deviceId, deviceName, "android")))
                        val hello = l.readHandshake(FrameType.HELLO).decode<Hello>()
                        if (hello.id != invite.hostId) throw ProtocolException("host id mismatch")
                        if (hello.paired != true) {
                            l.write(frameOf(FrameType.PAIR_REQUEST, PairRequest(deviceName, PairingInvite.proof(invite.secret, invite.hostId, deviceId))))
                            val result = l.readHandshake(FrameType.PAIR_RESULT).decode<PairResult>()
                            if (!result.ok) {
                                l.close()
                                lastError = when (result.reason) {
                                    "bad_proof" -> "The pairing code was not accepted"
                                    "window_closed" -> "The pairing code expired. Open a new one on the Mac"
                                    else -> "Pairing was refused (${result.reason})"
                                }
                                break
                            }
                        }
                        val code = PairingInvite.confirmCode(invite.secret, invite.hostId, deviceId)
                        val paired = HostPairing(invite.hostId, hello.name, addresses = invite.hosts).remember(address)
                        post {
                            setHosts(listOf(paired) + hosts.filterNot { it.hostId == paired.hostId })
                            listener.onPaired(hello.name, code)
                            attach(l, paired.hostId, address)
                        }
                        return@execute
                    } catch (e: IOException) {
                        l.close()
                        throw e
                    }
                } catch (e: IOException) {
                    lastError = "Could not reach the Mac at $address (${e.message})"
                    listener.log(lastError)
                }
            }
            post { listener.onPairingFailed(lastError) }
        }
    }

    fun unpair(hostId: String) = post {
        if (hostId == connectedHostId) link?.let { l -> runCatching { l.write(Frame.empty(FrameType.UNPAIRED)) } }
        forget(hostId)
    }

    /** Drops the current connection as if the network went away. Test hook. */
    internal fun dropConnection() = post { link?.close() }

    fun setClipboardSync(enabled: Boolean) = post { syncEnabled = enabled }

    /** Called when the phone clipboard changes (or when the user sends it explicitly). */
    fun localClipboardChanged(text: String, force: Boolean = false) = post {
        if ((!syncEnabled && !force) || text.isEmpty() || text.toByteArray().size > MAX_CLIP_BYTES) return@post
        val clip = Clip.of(deviceId, text)
        if (!force && (clip.sha256 == lastAppliedClip || clip.sha256 == lastSentClip)) return@post
        val l = link ?: return@post listener.log("Clipboard not sent: Mac is offline")
        lastSentClip = clip.sha256
        send(l, frameOf(FrameType.CLIP, clip))
    }

    fun sendText(text: String) = post {
        val l = link ?: return@post listener.log("Text not sent: Mac is offline")
        if (text.toByteArray().size > MAX_CLIP_BYTES) return@post
        send(l, frameOf(FrameType.OFFER, Offer(newWireId(), listOf(OfferItem.text(text)))))
    }

    /** Hashes the sources off the core thread, then offers them; re-offered on every reconnect until done. */
    fun sendFiles(sources: List<FileSource>) = io.execute {
        val items = mutableListOf<OfferItem>()
        val byId = mutableMapOf<String, FileSource>()
        for (source in sources) {
            val hash = runCatching { sha256Hex(source.open()) }.getOrElse { listener.log("Cannot read ${source.name}: $it"); continue }
            val item = OfferItem.file(source.name, source.size, source.mime, hash)
            items += item
            byId[item.fileId] = source
        }
        if (items.isEmpty()) return@execute
        post {
            val t = OutgoingTransfer(Offer(newWireId(), items), byId)
            outgoing[t.offer.xferId] = t
            setProgress(t.offer.xferId, TransferProgress.Direction.OUTGOING, t.title, 0, t.totalBytes, TransferProgress.Status.WAITING)
            link?.let { send(it, frameOf(FrameType.OFFER, t.offer)) }
        }
    }

    fun cancelTransfer(xferId: String) = post {
        outgoing[xferId]?.let {
            link?.let { l -> send(l, frameOf(FrameType.CANCEL, Cancel(xferId, "cancelled"))) }
            fail(it, "Cancelled")
            return@post
        }
        if (incoming.state(xferId) != null) {
            link?.let { l -> send(l, frameOf(FrameType.CANCEL, Cancel(xferId, "cancelled"))) }
            incoming.remove(xferId)
            updateProgress(xferId) { it.copy(status = TransferProgress.Status.FAILED, error = "Cancelled") }
        }
    }


    private fun connectLoop() {
        try {
            connectLoopBody()
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // stop() shut down the core executor mid-connect.
        } catch (_: InterruptedException) {
        }
    }

    private fun connectLoopBody() {
        var backoff = 0
        while (running.get()) {
            val targets = onCore { if (link == null) hosts else emptyList() }
            if (targets.isEmpty()) {
                waitForNudge(30_000)
                backoff = 0
                continue
            }
            onCore { if (link == null) setStatus(Status.CONNECTING) }
            if (connectToAny(targets)) {
                backoff = 0
            } else {
                onCore { if (link == null && hosts.isNotEmpty()) setStatus(Status.OFFLINE) }
                waitForNudge(BACKOFF_MS[backoff.coerceAtMost(BACKOFF_MS.lastIndex)])
                backoff++
            }
        }
    }

    /**
     * Tries the hosts discovery currently sees first, then the rest, each in most
     * recently connected order. Returns true once connected, or once the pairings
     * changed and the targets should be recomputed.
     */
    private fun connectToAny(targets: List<HostPairing>): Boolean {
        val found = targets.associate { it.hostId to discoveredAddresses(it.hostId) }
        for (target in targets.sortedByDescending { found.getValue(it.hostId).isNotEmpty() }) {
            for (address in (found.getValue(target.hostId) + target.knownAddresses).distinct()) {
                if (!running.get()) return true
                if (tryConnect(target, address) != Attempt.FAILED) return true
            }
        }
        return false
    }

    /** CONNECTED also covers a lost race with pair() or unpair(); UNPAIRED means the Mac forgot this phone. */
    private enum class Attempt { CONNECTED, UNPAIRED, FAILED }

    private fun tryConnect(target: HostPairing, address: String): Attempt {
        val (host, port) = parseHostPort(address) ?: return Attempt.FAILED
        try {
            val l = Link.open(host, port, identity, target.hostId)
            try {
                l.write(frameOf(FrameType.HELLO, Hello(1, deviceId, deviceName, "android")))
                val hello = l.readHandshake(FrameType.HELLO).decode<Hello>()
                if (hello.id != target.hostId) throw ProtocolException("host id mismatch")
                if (hello.paired == false) {
                    l.close()
                    onCore { forget(target.hostId) }
                    return Attempt.UNPAIRED
                }
                val attached = onCore {
                    val current = hosts.firstOrNull { it.hostId == target.hostId }
                    // Unpaired, or connected through pair(), while this attempt was running.
                    if (current == null || link != null) return@onCore false
                    val updated = current.copy(hostName = hello.name).remember(address)
                    setHosts(listOf(updated) + hosts.filterNot { it.hostId == updated.hostId })
                    attach(l, updated.hostId, address)
                    true
                }
                if (!attached) l.close()
                return Attempt.CONNECTED
            } catch (e: IOException) {
                l.close()
                throw e
            }
        } catch (e: IOException) {
            listener.log("Connect to $address failed: ${e.message}")
            return Attempt.FAILED
        }
    }

    private fun waitForNudge(ms: Long) {
        nudge.tryAcquire(ms, TimeUnit.MILLISECONDS)
        nudge.drainPermits()
    }

    private fun <T> onCore(block: () -> T): T = core.submit(block).get()

    private fun attach(l: Link, hostId: String, address: String) {
        link?.close()
        link = l
        connectedHostId = hostId
        setStatus(Status.CONNECTED)
        pushHosts()
        listener.log("Connected to $address")
        l.startReading(
            onFrame = { frame -> post { if (link === l) handle(l, frame) } },
            onClose = { error -> post { detached(l, error) } },
        )
        for (t in outgoing.values) {
            if (t.state == OutgoingTransfer.State.AWAITING_ANSWER || t.state == OutgoingTransfer.State.SENDING) {
                t.generation++
                t.state = OutgoingTransfer.State.AWAITING_ANSWER
                t.offeredAt = System.currentTimeMillis()
                send(l, frameOf(FrameType.OFFER, t.offer))
            }
        }
    }

    private fun detached(l: Link, error: Throwable?) {
        if (link !== l) return
        link = null
        connectedHostId = null
        outgoing.values.forEach { it.generation++ }
        listener.log("Disconnected${error?.let { ": ${it.message}" } ?: ""}")
        setStatus(if (hosts.isEmpty()) Status.UNPAIRED else Status.OFFLINE)
        pushHosts()
        wake()
    }

    /** Removes a pairing, from either side. Pending uploads wait for another paired Mac. */
    private fun forget(hostId: String) {
        val host = hosts.firstOrNull { it.hostId == hostId } ?: return
        setHosts(hosts - host)
        if (hostId == connectedHostId) {
            link?.close()
            link = null
            connectedHostId = null
            outgoing.values.forEach { it.generation++ }
        }
        if (hosts.isEmpty()) outgoing.values.toList().forEach { fail(it, "Unpaired") }
        setStatus(
            when {
                hosts.isEmpty() -> Status.UNPAIRED
                link != null -> Status.CONNECTED
                else -> Status.OFFLINE
            },
        )
        pushHosts()
        listener.onUnpaired(host.hostName)
        wake()
    }

    private fun setHosts(list: List<HostPairing>) {
        hosts = list
        pairingStore.save(list)
        pushHosts()
    }

    private fun pushHosts() = listener.onHosts(hosts, connectedHostId)

    private fun tick() {
        val l = link ?: return
        if (System.currentTimeMillis() - l.lastReceived > IDLE_TIMEOUT_MS) {
            listener.log("Host silent for 60 s; reconnecting")
            l.close()
            return
        }
        send(l, Frame.empty(FrameType.PING))
        val now = System.currentTimeMillis()
        outgoing.values.filter { it.state == OutgoingTransfer.State.AWAITING_ANSWER && now - it.offeredAt > OFFER_TIMEOUT_MS }.forEach {
            send(l, frameOf(FrameType.CANCEL, Cancel(it.offer.xferId, "timeout")))
            fail(it, "No answer from the Mac")
        }
    }

    private fun send(l: Link, frame: Frame) {
        try {
            l.write(frame)
        } catch (e: IOException) {
            l.close()
        }
    }


    private fun handle(l: Link, frame: Frame) {
        try {
            when (frame.type) {
                FrameType.PONG, FrameType.PING, FrameType.HELLO, null -> Unit
                FrameType.CLIP -> handleClip(frame.decode())
                FrameType.OFFER -> handleOffer(l, frame.decode())
                FrameType.CHUNK -> handleChunk(l, Chunk.parse(frame))
                FrameType.FILE_DONE -> handleFileDone(frame.decode())
                FrameType.ACCEPT -> handleAccept(frame.decode())
                FrameType.DECLINE -> outgoing[frame.decode<TransferRef>().xferId]?.let { fail(it, "Declined on the Mac") }
                FrameType.VERIFIED -> handleVerified(frame.decode())
                FrameType.HASH_FAIL -> handleHashFail(frame.decode())
                FrameType.CANCEL -> handleCancel(frame.decode())
                FrameType.UNPAIRED -> connectedHostId?.let(::forget)
                FrameType.PAIR_REQUEST, FrameType.PAIR_RESULT -> Unit
            }
        } catch (e: Exception) {
            listener.log("Protocol error: ${e.message}")
            l.close()
        }
    }

    private fun handleClip(clip: Clip) {
        if (!syncEnabled || !clip.isIntact || clip.text.toByteArray().size > MAX_CLIP_BYTES || clip.sha256 == lastAppliedClip) return
        lastAppliedClip = clip.sha256
        listener.onClipboard(clip.text)
    }

    private fun handleOffer(l: Link, offer: Offer) {
        val valid = isWireUuid(offer.xferId) && offer.items.isNotEmpty() && offer.items.all(::isValid)
        if (!valid) return send(l, frameOf(FrameType.CANCEL, Cancel(offer.xferId, "invalid_offer")))
        val inline = offer.items.filter { it.kind != ItemKind.FILE }
        if (inline.isNotEmpty() && deliveredItems.add(offer.xferId)) listener.onItems(inline)
        val files = offer.files
        if (files.isEmpty()) return
        if (incoming.state(offer.xferId) != null) return sendAccept(offer.xferId, title(files))
        if (!pendingDecisions.add(offer.xferId)) return
        val answered = AtomicBoolean(false)
        listener.onOffer(offer) { accepted ->
            if (answered.compareAndSet(false, true)) post { decided(offer, accepted) }
        }
    }

    private fun decided(offer: Offer, accepted: Boolean) {
        pendingDecisions.remove(offer.xferId)
        val l = link ?: return
        if (!accepted) return send(l, frameOf(FrameType.DECLINE, TransferRef(offer.xferId)))
        incoming.accept(offer, offer.files.map { it.fileId })
        sendAccept(offer.xferId, title(offer.files))
    }

    private fun sendAccept(xferId: String, title: String) {
        val l = link ?: return
        setProgress(xferId, TransferProgress.Direction.INCOMING, title, incoming.receivedBytes(xferId), incoming.totalBytes(xferId), TransferProgress.Status.ACTIVE)
        send(l, frameOf(FrameType.ACCEPT, Accept(xferId, incoming.offsets(xferId))))
    }

    private fun handleChunk(l: Link, chunk: Chunk) {
        val xferId = chunk.xferId.toString()
        try {
            incoming.write(chunk)
            updateProgress(xferId, throttled = true) { it.copy(bytesDone = incoming.receivedBytes(xferId)) }
        } catch (e: IOException) {
            listener.log("Rejecting chunk: ${e.message}")
            send(l, frameOf(FrameType.CANCEL, Cancel(xferId, "bad_chunk")))
            incoming.remove(xferId)
            updateProgress(xferId) { it.copy(status = TransferProgress.Status.FAILED, error = "Transfer error") }
        }
    }

    private fun handleFileDone(ref: FileRef) {
        val (part, item) = incoming.prepareFinish(ref.xferId, ref.fileId) ?: return
        io.execute {
            val ok = runCatching { IncomingStore.verify(part, item) }.getOrDefault(false)
            val saved = if (ok) runCatching { sink.commit(part, FileNames.sanitize(item.name ?: "file"), item.mime) } else null
            post {
                val l = link
                when {
                    saved == null -> {
                        part.delete()
                        l?.let { send(it, frameOf(FrameType.HASH_FAIL, ref)) }
                    }
                    saved.isSuccess -> {
                        incoming.markVerified(ref.xferId, ref.fileId)
                        l?.let { send(it, frameOf(FrameType.VERIFIED, ref)) }
                        val complete = incoming.isComplete(ref.xferId)
                        updateProgress(ref.xferId) {
                            if (complete) it.copy(bytesDone = it.bytesTotal, status = TransferProgress.Status.DONE) else it
                        }
                        listener.onFileReceived(saved.getOrThrow())
                    }
                    else -> {
                        listener.log("Could not save file: ${saved.exceptionOrNull()}")
                        l?.let { send(it, frameOf(FrameType.CANCEL, Cancel(ref.xferId, "receiver_error"))) }
                        incoming.remove(ref.xferId)
                        updateProgress(ref.xferId) { it.copy(status = TransferProgress.Status.FAILED, error = "Could not save file") }
                    }
                }
            }
        }
    }

    private fun handleAccept(accept: Accept) {
        val t = outgoing[accept.xferId]?.takeIf { it.state == OutgoingTransfer.State.AWAITING_ANSWER } ?: return
        t.queue.clear()
        for (item in t.offer.files) {
            val offset = accept.files[item.fileId] ?: continue
            if (item.fileId !in t.verified) t.queue.addLast(item.fileId to offset.coerceIn(0, item.size ?: 0))
        }
        t.state = OutgoingTransfer.State.SENDING
        updateProgress(accept.xferId) { it.copy(status = TransferProgress.Status.ACTIVE) }
        pumpNext(t)
    }

    private fun handleVerified(ref: FileRef) {
        val t = outgoing[ref.xferId] ?: return
        if (t.queue.firstOrNull()?.first != ref.fileId) return
        t.verified += ref.fileId
        t.queue.removeFirst()
        updateProgress(ref.xferId) { it.copy(bytesDone = t.verifiedBytes) }
        pumpNext(t)
    }

    private fun handleHashFail(ref: FileRef) {
        val t = outgoing[ref.xferId] ?: return
        if (t.queue.firstOrNull()?.first != ref.fileId) return
        if (!t.retried.add(ref.fileId)) {
            link?.let { send(it, frameOf(FrameType.CANCEL, Cancel(ref.xferId, "hash_mismatch"))) }
            return fail(t, "Integrity check failed")
        }
        t.queue[0] = ref.fileId to 0L
        pumpNext(t)
    }

    private fun handleCancel(cancel: Cancel) {
        outgoing[cancel.xferId]?.let { return fail(it, "Cancelled on the Mac") }
        if (incoming.state(cancel.xferId) != null) {
            incoming.remove(cancel.xferId)
            updateProgress(cancel.xferId) { it.copy(status = TransferProgress.Status.FAILED, error = "Cancelled on the Mac") }
        }
    }


    private fun pumpNext(t: OutgoingTransfer) {
        if (t.state != OutgoingTransfer.State.SENDING) return
        val l = link ?: return
        val (fileId, offset) = t.queue.firstOrNull() ?: run {
            t.state = OutgoingTransfer.State.DONE
            outgoing.remove(t.offer.xferId)
            updateProgress(t.offer.xferId) { it.copy(bytesDone = it.bytesTotal, status = TransferProgress.Status.DONE) }
            return
        }
        val source = t.sources[fileId] ?: return fail(t, "File is no longer available")
        val generation = ++t.generation
        val xfer = UUID.fromString(t.offer.xferId)
        val file = UUID.fromString(fileId)
        val base = t.verifiedBytes
        io.execute {
            try {
                source.open().use { input ->
                    var skipped = 0L
                    while (skipped < offset) {
                        val n = input.skip(offset - skipped)
                        if (n <= 0) throw IOException("source shorter than offset")
                        skipped += n
                    }
                    val buffer = ByteArray(Frame.MAX_CHUNK_DATA)
                    var position = offset
                    while (t.generation == generation && t.state == OutgoingTransfer.State.SENDING) {
                        val n = input.readUpTo(buffer)
                        if (n <= 0) break
                        l.write(Chunk(xfer, file, position, buffer, n).frame())
                        position += n
                        val sent = base + position
                        post { updateProgress(t.offer.xferId, throttled = true) { it.copy(bytesDone = sent) } }
                    }
                    if (t.generation == generation && t.state == OutgoingTransfer.State.SENDING) {
                        l.write(frameOf(FrameType.FILE_DONE, FileRef(t.offer.xferId, fileId)))
                    }
                }
            } catch (e: IOException) {
                listener.log("Upload interrupted: ${e.message}")
                l.close()
            }
        }
    }

    private fun fail(t: OutgoingTransfer, reason: String) {
        t.state = OutgoingTransfer.State.CANCELLED
        t.generation++
        outgoing.remove(t.offer.xferId)
        updateProgress(t.offer.xferId) { it.copy(status = TransferProgress.Status.FAILED, error = reason) }
    }


    private fun setStatus(s: Status) {
        if (s == status) return
        status = s
        listener.onStatus(s)
    }

    private fun setProgress(id: String, direction: TransferProgress.Direction, title: String, done: Long, total: Long, status: TransferProgress.Status) {
        progress[id] = TransferProgress(id, direction, title, done, total, status)
        while (progress.size > 20) progress.remove(progress.keys.first())
        pushProgress()
    }

    private fun updateProgress(id: String, throttled: Boolean = false, change: (TransferProgress) -> TransferProgress) {
        val p = progress[id] ?: return
        progress[id] = change(p)
        if (throttled && System.currentTimeMillis() - lastProgressPush < 250) return
        pushProgress()
    }

    private fun pushProgress() {
        lastProgressPush = System.currentTimeMillis()
        listener.onTransfers(progress.values.reversed())
    }

    private fun isValid(item: OfferItem): Boolean {
        if (!isWireUuid(item.fileId)) return false
        return when (item.kind) {
            ItemKind.FILE -> (item.size ?: -1) >= 0 && item.name != null && Hex.isSha256(item.sha256)
            ItemKind.TEXT, ItemKind.URL -> (item.text?.toByteArray()?.size ?: Int.MAX_VALUE) <= MAX_CLIP_BYTES
        }
    }

    private fun title(files: List<OfferItem>) = files.singleOrNull()?.let { FileNames.sanitize(it.name ?: "file") } ?: "${files.size} files"

    companion object {
        const val MAX_CLIP_BYTES = 1 shl 20
        const val PING_INTERVAL_MS = 20_000L
        const val IDLE_TIMEOUT_MS = 60_000L
        const val OFFER_TIMEOUT_MS = 300_000L
        val BACKOFF_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 16_000, 30_000)
    }
}

/** Fills [buffer] as far as the stream allows (InputStream.readNBytes needs API 33). */
private fun java.io.InputStream.readUpTo(buffer: ByteArray): Int {
    var total = 0
    while (total < buffer.size) {
        val n = read(buffer, total, buffer.size - total)
        if (n < 0) break
        total += n
    }
    return total
}

/** One TLS connection to the host. Writes are serialized; reading runs on its own thread. */
internal class Link private constructor(private val socket: SSLSocket) {
    private val reader = FrameReader(socket.inputStream)
    private val out: OutputStream = socket.outputStream.buffered(64 * 1024)
    private val closed = AtomicBoolean(false)
    @Volatile var lastReceived = System.currentTimeMillis()
        private set

    fun write(frame: Frame) {
        if (closed.get()) throw IOException("closed")
        synchronized(out) {
            out.write(frame.encoded())
            out.flush()
        }
    }

    /** Synchronous read used before the reader thread starts (HELLO / PAIR_RESULT). */
    fun readHandshake(expected: FrameType): Frame {
        while (true) {
            val frame = reader.read() ?: throw IOException("connection closed by the Mac")
            lastReceived = System.currentTimeMillis()
            if (frame.type == expected) return frame
            if (frame.type == FrameType.UNPAIRED) throw ProtocolException("unpaired")
        }
    }

    fun startReading(onFrame: (Frame) -> Unit, onClose: (Throwable?) -> Unit) {
        socket.soTimeout = 0
        Thread({
            var error: Throwable? = null
            try {
                while (true) {
                    val frame = reader.read() ?: break
                    lastReceived = System.currentTimeMillis()
                    onFrame(frame)
                }
            } catch (e: IOException) {
                error = e
            }
            close()
            onClose(error)
        }, "handspan-reader").apply { isDaemon = true }.start()
    }

    fun close() {
        if (closed.compareAndSet(false, true)) runCatching { socket.close() }
    }

    companion object {
        fun open(host: String, port: Int, identity: TlsIdentity, expectedHostId: String): Link {
            val socket = Tls.context(identity, expectedHostId).socketFactory.createSocket() as SSLSocket
            try {
                socket.enabledProtocols = arrayOf("TLSv1.3")
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), 5_000)
                socket.soTimeout = 15_000
                socket.startHandshake()
                return Link(socket)
            } catch (e: IOException) {
                runCatching { socket.close() }
                throw e
            }
        }
    }
}
