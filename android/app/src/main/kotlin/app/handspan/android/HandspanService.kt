package app.handspan.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.IBinder
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.handspan.android.ui.MainActivity
import app.handspan.core.PhoneClient
import app.handspan.core.TransferProgress

/**
 * Keeps the connection to the Mac alive while paired (a connectedDevice
 * foreground service) and renders Handspan's notifications.
 */
class HandspanService : Service(), Handspan.Notifier {
    private lateinit var notifications: NotificationManager
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = handspan.networkChanged()

        // Same Wi-Fi, new addresses (e.g. the router renewed its IPv6 prefix).
        override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) = handspan.networkChanged()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL_STATUS, getString(R.string.channel_status), NotificationManager.IMPORTANCE_MIN))
        notifications.createNotificationChannel(NotificationChannel(CHANNEL_INCOMING, getString(R.string.channel_incoming), NotificationManager.IMPORTANCE_HIGH))
        ServiceCompat.startForeground(this, ID_STATUS, statusNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        handspan.notifier = this
        handspan.start()
        connectivity.registerDefaultNetworkCallback(networkCallback)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ACCEPT, ACTION_DECLINE -> intent.getStringExtra(EXTRA_XFER)?.let { handspan.answer(it, intent.action == ACTION_ACCEPT) }
            ACTION_COPY -> intent.getStringExtra(EXTRA_TEXT)?.let(handspan::copyToClipboard)
            ACTION_SEND_URIS -> intent.clipData?.let { clip -> handspan.sendUris((0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }) }
        }
        if (!handspan.isPaired && !handspan.pairingInProgress.value) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        if (handspan.notifier === this) handspan.notifier = null
        super.onDestroy()
    }


    override fun statusChanged() {
        // Use the stored pairing rather than the status, which is still UNPAIRED
        // for a moment after pairing succeeds.
        if (!handspan.isPaired && !handspan.pairingInProgress.value) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        post(ID_STATUS, statusNotification())
    }

    override fun offer(offer: PendingOffer) {
        val id = offer.offer.xferId
        val notification = NotificationCompat.Builder(this, CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("${handspan.hostName.value ?: "Your Mac"} wants to send ${offer.title}")
            .setContentText(Formatter.formatShortFileSize(this, offer.totalBytes))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(activityIntent())
            .addAction(0, "Accept", serviceIntent(ACTION_ACCEPT, id.hashCode() * 2) { putExtra(EXTRA_XFER, id) })
            .addAction(0, "Decline", serviceIntent(ACTION_DECLINE, id.hashCode() * 2 + 1) { putExtra(EXTRA_XFER, id) })
            .setOngoing(true)
            .build()
        post(id.hashCode(), notification)
    }

    override fun offerResolved(xferId: String) = notifications.cancel(xferId.hashCode())

    override fun received(item: Received) {
        val from = handspan.hostName.value ?: "your Mac"
        val builder = NotificationCompat.Builder(this, CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_tile)
            .setAutoCancel(true)
        when (item.kind) {
            Received.Kind.CLIPBOARD -> return // Android shows its own "copied" confirmation.
            Received.Kind.LINK -> builder
                .setContentTitle("Link from $from")
                .setContentText(item.text)
                .setContentIntent(PendingIntent.getActivity(this, item.id.hashCode(), Intent(Intent.ACTION_VIEW, Uri.parse(item.text.trim())), PendingIntent.FLAG_IMMUTABLE))
                .addAction(0, "Copy", serviceIntent(ACTION_COPY, item.id.hashCode()) { putExtra(EXTRA_TEXT, item.text) })
            Received.Kind.TEXT -> builder
                .setContentTitle("Text from $from")
                .setContentText(item.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(item.text.take(1000)))
                .setContentIntent(activityIntent())
                .addAction(0, "Copy", serviceIntent(ACTION_COPY, item.id.hashCode()) { putExtra(EXTRA_TEXT, item.text) })
            Received.Kind.FILE -> {
                val file = item.file ?: return
                val view = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(file.location), file.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                builder.setContentTitle("File from $from")
                    .setContentText(file.displayName)
                    .setContentIntent(PendingIntent.getActivity(this, item.id.hashCode(), view, PendingIntent.FLAG_IMMUTABLE))
            }
        }
        post(item.id.hashCode(), builder.build())
    }

    override fun transfers(list: List<TransferProgress>) {
        val active = list.filter { it.status == TransferProgress.Status.ACTIVE }
        if (active.isEmpty()) return notifications.cancel(ID_PROGRESS)
        val done = active.sumOf { it.bytesDone }
        val total = active.sumOf { it.bytesTotal }.coerceAtLeast(1)
        val title = active.singleOrNull()?.let { (if (it.direction == TransferProgress.Direction.INCOMING) "Receiving " else "Sending ") + it.title }
            ?: "${active.size} transfers"
        val notification = NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(title)
            .setProgress(1000, (done * 1000 / total).toInt(), false)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(activityIntent())
            .build()
        post(ID_PROGRESS, notification)
    }


    private fun statusNotification(): Notification {
        val status = handspan.status.value
        val host = handspan.hostName.value ?: "Mac"
        val several = handspan.hosts.value.size > 1
        val text = when (status) {
            PhoneClient.Status.CONNECTED -> "Connected to $host"
            PhoneClient.Status.CONNECTING -> if (several) "Looking for your Macs…" else "Looking for $host…"
            PhoneClient.Status.OFFLINE -> if (several) "None of your Macs is reachable on this network" else "$host is not reachable on this network"
            PhoneClient.Status.UNPAIRED -> "Pairing…"
        }
        val sendClipboard = PendingIntent.getActivity(
            this, 1,
            Intent(this, ClipboardSendActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Handspan")
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(activityIntent())
            .apply { if (status == PhoneClient.Status.CONNECTED) addAction(0, "Send clipboard", sendClipboard) }
            .build()
    }

    private fun activityIntent() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun serviceIntent(action: String, requestCode: Int, extras: Intent.() -> Unit) = PendingIntent.getService(
        this, requestCode, Intent(this, HandspanService::class.java).setAction(action).apply(extras),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun post(id: Int, notification: Notification) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            notifications.notify(id, notification)
        }
    }

    companion object {
        private const val CHANNEL_STATUS = "status"
        private const val CHANNEL_INCOMING = "incoming"
        private const val ID_STATUS = 1
        private const val ID_PROGRESS = 2
        private const val ACTION_ACCEPT = "app.handspan.ACCEPT"
        private const val ACTION_DECLINE = "app.handspan.DECLINE"
        private const val ACTION_COPY = "app.handspan.COPY"
        private const val ACTION_SEND_URIS = "app.handspan.SEND_URIS"
        private const val EXTRA_XFER = "xferId"
        private const val EXTRA_TEXT = "text"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, HandspanService::class.java))
        }

        /** Sends content URIs; the read grant moves to the service, which outlives the caller. */
        fun sendUris(context: Context, uris: List<Uri>) {
            if (uris.isEmpty()) return
            val clip = android.content.ClipData.newRawUri("files", uris.first()).apply { uris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) } }
            val intent = Intent(context, HandspanService::class.java)
                .setAction(ACTION_SEND_URIS)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = clip
            ContextCompat.startForegroundService(context, intent)
        }
    }
}

/** Reconnects after a reboot or app update without opening the app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (context.handspan.isPaired) runCatching { HandspanService.start(context) }
    }
}
