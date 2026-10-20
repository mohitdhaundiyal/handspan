package app.handspan.android

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.core.content.IntentCompat

/**
 * Invisible activity that takes focus just long enough to read the clipboard
 * (Android 10+ blocks background reads) and send it to the Mac.
 */
class ClipboardSendActivity : Activity() {
    private var attempts = 0

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && attempts == 0) trySend()
    }

    private fun trySend() {
        if (isFinishing) return
        attempts++
        val text = handspan.readClipboardText(this)
        // Focus can arrive a moment before the clipboard service sees us as focused
        // (e.g. while the notification shade collapses); retry briefly before giving up.
        if (text == null && attempts < 4) {
            window.decorView.postDelayed(::trySend, 150)
            return
        }
        val message = when {
            text == null -> "Clipboard is empty"
            handspan.sendClipboard(text) -> "Clipboard sent to ${handspan.hostName.value ?: "Mac"}"
            else -> "Mac is not connected"
        }
        Log.i("Handspan", "Send clipboard: $message (attempt $attempts)")
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }
}

/** "Share → Send to Mac" from any app: files, links or text. */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        // Some apps share URIs without granting read access; passing those on would throw.
        val uris = shared.filter { checkCallingOrSelfUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val message = when {
            !handspan.isPaired -> "Pair Handspan with your Mac first"
            shared.isNotEmpty() && uris.isEmpty() -> "The sharing app didn't allow Handspan to read this file"
            uris.isNotEmpty() -> {
                // A share grant ends with this activity; uploads may resume much later,
                // so hand the URIs (and the grant) to the long-running service.
                HandspanService.sendUris(this, uris)
                "Sending ${if (uris.size == 1) "file" else "${uris.size} files"} to ${handspan.hostName.value ?: "Mac"}"
            }
            text != null && handspan.sendText(text) -> "Sent to ${handspan.hostName.value ?: "Mac"}"
            text != null -> "Mac is not connected"
            else -> "Nothing to send"
        }
        if (handspan.isPaired && uris.isEmpty()) HandspanService.start(this)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }
}

/** Quick Settings tile: one tap sends the clipboard. */
class SendClipboardTile : TileService() {
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, ClipboardSendActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
