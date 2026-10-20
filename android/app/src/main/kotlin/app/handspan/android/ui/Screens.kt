package app.handspan.android.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.handspan.android.HandspanService
import app.handspan.android.Received
import app.handspan.android.UiEvent
import app.handspan.android.handspan
import app.handspan.core.PhoneClient
import app.handspan.core.TransferProgress

@Composable
fun HandspanScreen() {
    val context = LocalContext.current
    val h = context.handspan
    val status by h.status.collectAsState()
    val pairing by h.pairingInProgress.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val pairedDialog by h.pairedResult.collectAsState()

    LaunchedEffect(Unit) {
        h.events.collect { event ->
            when (event) {
                is UiEvent.Paired -> Unit
                is UiEvent.Message -> snackbar.showSnackbar(event.text)
            }
        }
    }

    pairedDialog?.let { event ->
        AlertDialog(
            onDismissRequest = h::pairedResultShown,
            confirmButton = { TextButton(onClick = h::pairedResultShown) { Text("Done") } },
            title = { Text("Paired with ${event.hostName}") },
            text = { Text("Confirmation code ${event.code}. It should match the code shown on your Mac.") },
        )
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (status == PhoneClient.Status.UNPAIRED) PairScreen(pairing) else HomeScreen(status)
        }
    }
}

@Composable
private fun PairScreen(pairing: Boolean) {
    val context = LocalContext.current
    var scanning by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Handspan", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Share your clipboard, links and files with your Mac over your local Wi-Fi. Nothing leaves your network.",
            style = MaterialTheme.typography.bodyLarge,
        )
        when {
            pairing -> {
                Spacer(Modifier.size(24.dp))
                CircularProgressIndicator()
                Text("Pairing…")
            }
            scanning -> {
                Text("On your Mac, click the Handspan icon in the menu bar and choose “Pair new device…”, then point the camera at the code.")
                QrScanner(
                    Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)),
                ) { uri ->
                    scanning = false
                    HandspanService.start(context)
                    if (!context.handspan.pair(uri)) Toast.makeText(context, "That is not a Handspan pairing code", Toast.LENGTH_SHORT).show()
                }
                TextButton(onClick = { scanning = false }) { Text("Cancel") }
            }
            else -> {
                Text("Install Handspan on your Mac, make sure both devices are on the same network, then scan the pairing code.")
                Button(onClick = { scanning = true }) { Text("Scan pairing code") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(status: PhoneClient.Status) {
    val context = LocalContext.current
    val h = context.handspan
    val host by h.hostName.collectAsState()
    val sync by h.syncEnabled.collectAsState()
    val transfers by h.transfers.collectAsState()
    val received by h.received.collectAsState()
    val offers by h.offers.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var confirmUnpair by remember { mutableStateOf(false) }
    var composeText by remember { mutableStateOf(false) }
    val connected = status == PhoneClient.Status.CONNECTED

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        // Keep read access across app restarts so an interrupted upload can resume.
        uris.forEach { runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        h.sendUris(uris)
    }

    offers.firstOrNull()?.let { pending ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("${host ?: "Your Mac"} wants to send ${pending.title}") },
            text = { Text("${Formatter.formatShortFileSize(context, pending.totalBytes)} · saved to Download/Handspan") },
            confirmButton = { TextButton(onClick = { h.answer(pending.offer.xferId, true) }) { Text("Accept") } },
            dismissButton = { TextButton(onClick = { h.answer(pending.offer.xferId, false) }) { Text("Decline") } },
        )
    }
    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text("Unpair from ${host ?: "Mac"}?") },
            text = { Text("You will need to scan a new pairing code to connect again.") },
            confirmButton = { TextButton(onClick = { confirmUnpair = false; h.unpair() }) { Text("Unpair") } },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("Cancel") } },
        )
    }
    if (composeText) SendTextDialog(onDismiss = { composeText = false }) { text ->
        composeText = false
        if (!h.sendText(text)) Toast.makeText(context, "Mac is not connected", Toast.LENGTH_SHORT).show()
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Handspan") },
            actions = {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Unpair") }, onClick = { menu = false; confirmUnpair = true })
                    }
                }
            },
        )
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StatusCard(host ?: "Mac", status, onRetry = h::networkChanged) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Clipboard sync", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Mac → phone is automatic. Phone → Mac is automatic while Handspan is open; " +
                                        "elsewhere use the “Send clipboard” tile or notification button.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = sync, onCheckedChange = h::setSyncEnabled, modifier = Modifier.semantics { contentDescription = "Clipboard sync" })
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    FilledTonalButton(onClick = {
                        val text = h.readClipboardText(context)
                        val msg = when {
                            text == null -> "Clipboard is empty"
                            h.sendClipboard(text) -> "Clipboard sent"
                            else -> "Mac is not connected"
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }, enabled = connected, modifier = Modifier.weight(1.2f), contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Clipboard", maxLines = 1, softWrap = false)
                    }
                    FilledTonalButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Files", maxLines = 1)
                    }
                    FilledTonalButton(onClick = { composeText = true }, enabled = connected, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Text", maxLines = 1)
                    }
                }
            }
            if (transfers.isNotEmpty()) {
                item { SectionTitle("Transfers") }
                items(transfers, key = { it.id }) { TransferRow(it) }
            }
            if (received.isNotEmpty()) {
                item { SectionTitle("From your Mac") }
                items(received, key = { it.id }) { ReceivedRow(it) }
            }
        }
    }
}

@Composable
private fun StatusCard(host: String, status: PhoneClient.Status, onRetry: () -> Unit) {
    val (label, color) = when (status) {
        PhoneClient.Status.CONNECTED -> "Connected" to Color(0xFF2E7D32)
        PhoneClient.Status.CONNECTING -> "Looking for your Mac…" to MaterialTheme.colorScheme.tertiary
        else -> "Not reachable. Check that both devices are on the same network" to MaterialTheme.colorScheme.outline
    }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = color, modifier = Modifier.size(12.dp)) {}
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(host, style = MaterialTheme.typography.titleMedium)
                Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (status == PhoneClient.Status.OFFLINE) TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun TransferRow(t: TransferProgress) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text((if (t.direction == TransferProgress.Direction.INCOMING) "↓ " else "↑ ") + t.title, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            when (t.status) {
                TransferProgress.Status.WAITING -> Text("Waiting for the Mac…", style = MaterialTheme.typography.bodySmall)
                TransferProgress.Status.ACTIVE -> LinearProgressIndicator(
                    progress = { if (t.bytesTotal > 0) t.bytesDone.toFloat() / t.bytesTotal else 0f },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
                TransferProgress.Status.DONE -> Text("Done · ${Formatter.formatShortFileSize(context, t.bytesTotal)}", style = MaterialTheme.typography.bodySmall)
                TransferProgress.Status.FAILED -> Text(t.error ?: "Failed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        if (t.status == TransferProgress.Status.ACTIVE || t.status == TransferProgress.Status.WAITING) {
            TextButton(onClick = { context.handspan.cancelTransfer(t.id) }) { Text("Cancel") }
        }
    }
}

@Composable
private fun ReceivedRow(item: Received) {
    val context = LocalContext.current
    fun open(intent: Intent) = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app can open this", Toast.LENGTH_SHORT).show()
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        val icon = when (item.kind) {
            Received.Kind.LINK -> Icons.Default.Link
            Received.Kind.TEXT -> Icons.AutoMirrored.Filled.Notes
            Received.Kind.FILE -> Icons.Default.Description
            Received.Kind.CLIPBOARD -> Icons.Default.ContentCopy
        }
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(item.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        when (item.kind) {
            Received.Kind.LINK -> TextButton(onClick = { open(Intent(Intent.ACTION_VIEW, Uri.parse(item.text.trim()))) }) { Text("Open") }
            Received.Kind.TEXT -> TextButton(onClick = { context.handspan.copyToClipboard(item.text) }) { Text("Copy") }
            Received.Kind.FILE -> item.file?.let { f ->
                TextButton(onClick = {
                    open(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(f.location), f.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }) { Text("Open") }
            }
            Received.Kind.CLIPBOARD -> Text("Copied", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SendTextDialog(onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send text or a link") },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, minLines = 3, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onSend(text) }, enabled = text.isNotBlank()) { Text("Send") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
