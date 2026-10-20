package app.handspan.android

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.handspan.core.ItemKind
import app.handspan.core.Offer
import app.handspan.core.OfferItem
import app.handspan.core.PairingInvite
import app.handspan.core.PhoneClient
import app.handspan.core.PhoneListener
import app.handspan.core.ReceivedFile
import app.handspan.core.TransferProgress
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.UUID

class HandspanApp : Application() {
    lateinit var handspan: Handspan
        private set

    override fun onCreate() {
        super.onCreate()
        handspan = Handspan(this)
    }
}

val Context.handspan: Handspan get() = (applicationContext as HandspanApp).handspan

/** Something that arrived from the Mac, shown on the home screen. */
data class Received(val id: String = UUID.randomUUID().toString(), val kind: Kind, val text: String, val file: ReceivedFile? = null) {
    enum class Kind { TEXT, LINK, FILE, CLIPBOARD }
}

data class PendingOffer(val offer: Offer, val decide: (Boolean) -> Unit) {
    val title: String get() = offer.files.singleOrNull()?.name ?: "${offer.files.size} files"
    val totalBytes: Long get() = offer.files.sumOf { it.size ?: 0 }
}

sealed interface UiEvent {
    data class Paired(val hostName: String, val code: String) : UiEvent
    data class Message(val text: String) : UiEvent
}

/**
 * App-wide controller: owns the [PhoneClient], discovery and the state the UI
 * and notifications render. PhoneClient callbacks arrive on its core thread and
 * are hopped to the main thread here.
 */
class Handspan(private val app: Application) {
    private val main = Handler(Looper.getMainLooper())
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val pairingStore = PrefsPairingStore(app)
    private val clipboard = app.getSystemService(ClipboardManager::class.java)

    private val _status = MutableStateFlow(if (pairingStore.load() == null) PhoneClient.Status.UNPAIRED else PhoneClient.Status.OFFLINE)
    val status: StateFlow<PhoneClient.Status> = _status.asStateFlow()
    private val _hostName = MutableStateFlow(pairingStore.load()?.hostName)
    val hostName: StateFlow<String?> = _hostName.asStateFlow()
    private val _transfers = MutableStateFlow<List<TransferProgress>>(emptyList())
    val transfers: StateFlow<List<TransferProgress>> = _transfers.asStateFlow()
    private val _received = MutableStateFlow<List<Received>>(emptyList())
    val received: StateFlow<List<Received>> = _received.asStateFlow()
    private val _offers = MutableStateFlow<List<PendingOffer>>(emptyList())
    val offers: StateFlow<List<PendingOffer>> = _offers.asStateFlow()
    private val _syncEnabled = MutableStateFlow(prefs.getBoolean("sync", true))
    val syncEnabled: StateFlow<Boolean> = _syncEnabled.asStateFlow()
    private val _pairing = MutableStateFlow(false)
    val pairingInProgress: StateFlow<Boolean> = _pairing.asStateFlow()
    /** Held until the UI shows it: pairing often completes before the screen starts collecting. */
    private val _pairedResult = MutableStateFlow<UiEvent.Paired?>(null)
    val pairedResult: StateFlow<UiEvent.Paired?> = _pairedResult.asStateFlow()
    fun pairedResultShown() { _pairedResult.value = null }

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    /** Notification hooks, set by the running service. */
    var notifier: Notifier? = null

    interface Notifier {
        fun statusChanged()
        fun offer(offer: PendingOffer)
        fun offerResolved(xferId: String)
        fun received(item: Received)
        fun transfers(list: List<TransferProgress>)
    }

    private val discovery = Discovery(app, { pairingStore.load()?.hostId }) { client.wake() }

    private val client: PhoneClient = PhoneClient(
        identity = KeystoreIdentity.loadOrCreate(),
        deviceName = deviceName(app),
        pairingStore = pairingStore,
        partsDir = File(app.filesDir, "parts"),
        sink = MediaStoreSink(app),
        listener = Listener(),
        discoveredAddresses = { discovery.addresses },
    ).also { it.setClipboardSync(_syncEnabled.value) }

    val isPaired: Boolean get() = pairingStore.load() != null

    private var started = false

    /** Starts connecting and browsing. Called by the foreground service. */
    fun start() {
        if (started) return
        started = true
        client.start()
        discovery.start()
    }

    fun stop() {
        discovery.stop()
    }

    private var lastRetry = 0L

    /** Looks for the Mac again now: the network changed or the user asked to retry. */
    fun networkChanged() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastRetry < 2_000) return
        lastRetry = now
        discovery.refresh()
        client.wake()
    }


    fun pair(uri: String): Boolean {
        val invite = PairingInvite.parse(uri) ?: return false
        _pairing.value = true
        start()
        client.pair(invite)
        return true
    }

    fun unpair() = client.unpair()

    fun setSyncEnabled(enabled: Boolean) {
        _syncEnabled.value = enabled
        prefs.edit().putBoolean("sync", enabled).apply()
        client.setClipboardSync(enabled)
    }

    /** Automatic path: called while Handspan has focus and the clipboard changed. */
    fun clipboardChanged(text: String) = client.localClipboardChanged(text)

    /** Explicit "Send clipboard" (tile, notification, button): sends even if sync is paused. */
    fun sendClipboard(text: String): Boolean {
        if (_status.value != PhoneClient.Status.CONNECTED) return false
        client.localClipboardChanged(text, force = true)
        return true
    }

    fun sendText(text: String): Boolean {
        if (_status.value != PhoneClient.Status.CONNECTED) return false
        client.sendText(text)
        return true
    }

    fun sendUris(uris: List<Uri>) {
        Thread {
            val sources = uris.mapNotNull { runCatching { UriFileSource(app, it) }.getOrNull() }
            if (sources.isNotEmpty()) client.sendFiles(sources)
        }.start()
    }

    fun answer(xferId: String, accept: Boolean) {
        val pending = _offers.value.firstOrNull { it.offer.xferId == xferId } ?: return
        _offers.update { list -> list.filterNot { it.offer.xferId == xferId } }
        notifier?.offerResolved(xferId)
        pending.decide(accept)
    }

    fun cancelTransfer(xferId: String) = client.cancelTransfer(xferId)

    fun copyToClipboard(text: String) {
        // Writing is allowed from the background; only reads are restricted.
        clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
    }

    /** True if the current clip was written by us (so we don't send it back). */
    fun isOwnClip(): Boolean = clipboard.primaryClipDescription?.label == CLIP_LABEL

    fun readClipboardText(context: Context): String? =
        clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.takeIf { it.isNotEmpty() }

    private fun addReceived(item: Received) {
        _received.update { (listOf(item) + it).take(10) }
        notifier?.received(item)
    }

    private fun ui(block: () -> Unit) {
        main.post(block)
    }

    private inner class Listener : PhoneListener {
        override fun onStatus(status: PhoneClient.Status) = ui {
            _status.value = status
            _hostName.value = pairingStore.load()?.hostName
            notifier?.statusChanged()
        }

        override fun onPaired(hostName: String, code: String) = ui {
            _pairing.value = false
            _hostName.value = hostName
            _pairedResult.value = UiEvent.Paired(hostName, code)
            notifier?.statusChanged()
        }

        override fun onPairingFailed(reason: String) = ui {
            _pairing.value = false
            _events.tryEmit(UiEvent.Message(reason))
        }

        override fun onUnpaired() = ui {
            _hostName.value = null
            _offers.value.forEach { notifier?.offerResolved(it.offer.xferId) }
            _offers.value = emptyList()
            _events.tryEmit(UiEvent.Message("This phone is no longer paired with the Mac"))
            notifier?.statusChanged()
        }

        override fun onClipboard(text: String) = ui {
            copyToClipboard(text)
            addReceived(Received(kind = Received.Kind.CLIPBOARD, text = text))
        }

        override fun onItems(items: List<OfferItem>) = ui {
            for (item in items) {
                val text = item.text ?: continue
                addReceived(Received(kind = if (item.kind == ItemKind.URL) Received.Kind.LINK else Received.Kind.TEXT, text = text))
            }
        }

        override fun onOffer(offer: Offer, decide: (Boolean) -> Unit) = ui {
            val pending = PendingOffer(offer, decide)
            _offers.update { it + pending }
            notifier?.offer(pending)
        }

        override fun onFileReceived(file: ReceivedFile) = ui {
            addReceived(Received(kind = Received.Kind.FILE, text = file.displayName, file = file))
        }

        override fun onTransfers(transfers: List<TransferProgress>) = ui {
            _transfers.value = transfers
            notifier?.transfers(transfers)
        }

        override fun log(message: String) {
            Log.i("Handspan", message)
        }
    }

    companion object {
        const val CLIP_LABEL = "Handspan"
    }
}
