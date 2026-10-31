package app.handspan.android

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Browses `_handspan._tcp` and keeps the addresses of instances whose TXT `id`
 * matches one of the paired hosts (PROTOCOL §2). [onFound] fires when a match appears.
 *
 * Android stops a browse when the network it started on goes away (Wi-Fi
 * reconnects, router restarts), so the browse is restarted whenever that
 * happens, on [refresh], and when it has found nothing for a while.
 */
class Discovery(context: Context, private val pairedHostIds: () -> Collection<String>, private val onFound: () -> Unit) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private data class Instance(val hostId: String, val addresses: List<String>)

    private val found = ConcurrentHashMap<String, Instance>()
    private val callbacks = ConcurrentHashMap<String, NsdManager.ServiceInfoCallback>()
    private var multicastLock: WifiManager.MulticastLock? = null
    private var browsing: NsdManager.DiscoveryListener? = null
    private var wanted = false
    private var startedAt = 0L
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    /** Current addresses of a paired host. Restarts a browse that has found nothing for a while. */
    fun addresses(hostId: String): List<String> {
        if (found.isEmpty() && SystemClock.elapsedRealtime() - startedAt > STALE_MS) executor.execute(::restart)
        return found.values.filter { it.hostId == hostId }.flatMap { it.addresses }.distinct()
    }

    @Synchronized
    fun start() {
        wanted = true
        if (browsing != null) return
        if (multicastLock == null) {
            multicastLock = wifi.createMulticastLock("handspan-mdns").apply { setReferenceCounted(false); acquire() }
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) = resolve(info)
            override fun onServiceLost(info: NsdServiceInfo) = forget(info.serviceName)

            override fun onDiscoveryStopped(serviceType: String) = browseEnded(this, "stopped")
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = browseEnded(this, "failed to start ($errorCode)")
        }
        browsing = listener
        startedAt = SystemClock.elapsedRealtime()
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { browseEnded(listener, "failed to start (${it.message})") }
    }

    @Synchronized
    fun stop() {
        wanted = false
        endBrowse()
        multicastLock?.release()
        multicastLock = null
    }

    /** Starts a fresh browse, e.g. after a network change or when the user asks to retry. */
    fun refresh() = executor.execute(::restart)

    @Synchronized
    private fun restart() {
        if (!wanted) return
        endBrowse()
        start()
    }

    private fun browseEnded(listener: NsdManager.DiscoveryListener, why: String) {
        synchronized(this) {
            if (browsing !== listener) return
            browsing = null
        }
        Log.i(TAG, "Discovery $why; restarting")
        executor.schedule(::restart, RETRY_MS, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    private fun endBrowse() {
        browsing?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        browsing = null
        if (Build.VERSION.SDK_INT >= 34) callbacks.values.forEach { runCatching { nsd.unregisterServiceInfoCallback(it) } }
        callbacks.clear()
        found.clear()
    }

    private fun forget(serviceName: String) {
        found.remove(serviceName)
        if (Build.VERSION.SDK_INT >= 34) callbacks.remove(serviceName)?.let { runCatching { nsd.unregisterServiceInfoCallback(it) } }
    }

    private fun resolve(info: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= 34) {
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = Unit
                override fun onServiceUpdated(updated: NsdServiceInfo) = record(updated, updated.hostAddresses)
                override fun onServiceLost() { found.remove(info.serviceName) }
                override fun onServiceInfoCallbackUnregistered() = Unit
            }
            callbacks.put(info.serviceName, callback)?.let { runCatching { nsd.unregisterServiceInfoCallback(it) } }
            runCatching { nsd.registerServiceInfoCallback(info, executor, callback) }
        } else {
            // Before API 34 only one resolve may run at a time.
            synchronized(resolveQueue) { resolveQueue.addLast(info) }
            resolveNextLegacy()
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveNextLegacy() {
        val next = synchronized(resolveQueue) {
            if (resolving) return
            resolveQueue.removeFirstOrNull()?.also { resolving = true }
        } ?: return
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = done()
            override fun onServiceResolved(info: NsdServiceInfo) {
                record(info, listOfNotNull(info.host))
                done()
            }

            private fun done() {
                synchronized(resolveQueue) { resolving = false }
                resolveNextLegacy()
            }
        })
    }

    private fun record(info: NsdServiceInfo, hosts: List<InetAddress>) {
        val id = info.attributes["id"]?.decodeToString()?.takeIf { it.length == 16 } ?: return
        val hostId = pairedHostIds().firstOrNull { it.startsWith(id) } ?: return
        // Link-local IPv6 needs a scope ID that does not survive as text. IPv4 comes first:
        // it is stable for the lease, while global IPv6 addresses are often temporary.
        val addresses = hosts.filterNot { it is Inet6Address && it.isLinkLocalAddress }
            .map { if (it is Inet6Address) "[${it.hostAddress}]:${info.port}" else "${it.hostAddress}:${info.port}" }
            .sortedBy { it.startsWith("[") }
        if (addresses.isEmpty()) return
        found[info.serviceName] = Instance(hostId, addresses)
        onFound()
    }

    companion object {
        const val SERVICE_TYPE = "_handspan._tcp"
        private const val TAG = "HandspanDiscovery"
        private const val RETRY_MS = 2_000L
        private const val STALE_MS = 30_000L
    }
}
