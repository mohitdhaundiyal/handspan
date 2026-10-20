package app.handspan.android.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import app.handspan.android.HandspanService
import app.handspan.android.handspan
import app.handspan.core.PhoneClient

class MainActivity : ComponentActivity() {
    private val clipboard by lazy { getSystemService(ClipboardManager::class.java) }
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { syncClipboard() }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (handspan.isPaired) HandspanService.start(this)
        handlePairingLink(intent)
        setContent { HandspanTheme { HandspanScreen() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePairingLink(intent)
    }

    override fun onResume() {
        super.onResume()
        clipboard.addPrimaryClipChangedListener(clipListener)
        if (handspan.isPaired && handspan.status.value != PhoneClient.Status.CONNECTED) handspan.networkChanged()
    }

    override fun onPause() {
        clipboard.removePrimaryClipChangedListener(clipListener)
        super.onPause()
    }

    /** Android 10+ lets only the focused app read the clipboard, so sync whenever we gain focus. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) syncClipboard()
    }

    private fun syncClipboard() {
        if (!handspan.isPaired || !handspan.syncEnabled.value || handspan.isOwnClip()) return
        handspan.readClipboardText(this)?.let(handspan::clipboardChanged)
    }

    private fun handlePairingLink(intent: Intent?) {
        val uri = intent?.data?.toString() ?: return
        if (!uri.startsWith("handspan://pair")) return
        HandspanService.start(this)
        handspan.pair(uri)
    }
}

@Composable
fun HandspanTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
