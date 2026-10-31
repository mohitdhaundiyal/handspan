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
import androidx.compose.material.icons.filled.Add
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
import app.handspan.core.HostPairing
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
    var addingMac by remember { mutableStateOf(false) }

    LaunchedEffect(pairedDialog) { if (pairedDialog != null) addingMac = false }

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
            when {
                status == PhoneClient.Status.UNPAIRED -> PairScreen(pairing, onCancel = null)
                addingMac -> PairScreen(pairing, onCancel = { addingMac = false })
                else -> HomeScreen(status, onPairAnother = { addingMac = true })
            }
        }
    }
}

/** First pairing, or, with [onCancel], pairing an additional Mac. */
@Composable
private fun PairScreen(pairing: Boolean, onCancel: (() -> Unit)?) {
    val context = LocalContext.current
    var scanning by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (onCancel == null) {
            Text("Handspan", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Share your clipboard, links and files with your Mac over your local Wi-Fi. Nothing leaves your network.",
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            Text("Pair another Mac", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Pair with another Mac, or another account on the same Mac. Your other Macs stay paired, and the phone connects to whichever one is active.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
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
                if (onCancel != null) TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(status: PhoneClient.Status, onPairAnother: () -> Unit) {
    val context = LocalContext.current
    val h = context.handspan
    val host by h.hostName.collectAsState()
    val hosts by h.hosts.collectAsState()
    val connectedHostId by h.connectedHostId.collectAsState()
    val sync by h.syncEnabled.collectAsState()
    val transfers by h.transfers.collectAsState()
    val received by h.received.collectAsState()
    val offers by h.offers.collectAsState()
    var confirmUnpair by remember { mutableStateOf<HostPairing?>(null) }
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
    confirmUnpair?.let { mac ->
        AlertDialog(
            onDismissRequest = { confirmUnpair = null },
            title = { Text("Unpair from ${mac.hostName}?") },
            text = { Text("You will need to scan a new pairing code to connect to it again.") },
            confirmButton = { TextButton(onClick = { confirmUnpair = null; h.unpair(mac.hostId) }) { Text("Unpair") } },
            dismissButton = { TextButton(onClick = { confirmUnpair = null }) { Text("Cancel") } },
        )
    }
    if (composeText) SendTextDialog(onDismiss = { composeText = false }) { text ->
        composeText = false
        if (!h.sendText(text)) Toast.makeText(context, "Mac is not connected", Toast.LENGTH_SHORT).show()
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Handspan") })
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                MacsCard(
                    hosts = hosts,
                    connectedHostId = connectedHostId,
                    status = status,
                    onRetry = h::networkChanged,
                    onUnpair = { confirmUnpair = it },
                    onPairAnother = onPairAnother,
                )
            }
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

/** The paired Macs: which one is connected, and the others' state. */
@Composable
private fun MacsCard(
    hosts: List<HostPairing>,
    connectedHostId: String?,
    status: PhoneClient.Status,
    onRetry: () -> Unit,
    onUnpair: (HostPairing) -> Unit,
    onPairAnother: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
            for (mac in hosts) MacRow(mac, connectedHostId, status, onUnpair)
            if (connectedHostId == null && status == PhoneClient.Status.OFFLINE) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Check that the phone and the Mac are on the same network",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
            }
            TextButton(onClick = onPairAnother, modifier = Modifier.padding(horizontal = 8.dp)) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Pair another Mac")
            }
        }
    }
}

@Composable
private fun MacRow(mac: HostPairing, connectedHostId: String?, status: PhoneClient.Status, onUnpair: (HostPairing) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val connected = mac.hostId == connectedHostId
    val (label, color) = when {
        connected -> "Connected" to Color(0xFF2E7D32)
        connectedHostId != null -> "Not connected" to MaterialTheme.colorScheme.outline
        status == PhoneClient.Status.CONNECTING -> "Looking for it…" to MaterialTheme.colorScheme.tertiary
        else -> "Not reachable" to MaterialTheme.colorScheme.outline
    }
    Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = color, modifier = Modifier.size(12.dp)) {}
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(mac.hostName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Options for ${mac.hostName}") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Unpair") }, onClick = { menu = false; onUnpair(mac) })
            }
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
