package dev.nura.app.field.sync.feature.transfer

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nura.app.field.sync.core.sync.TransferPhase
import dev.nura.app.field.sync.core.sync.TransferStats
import dev.nura.app.field.sync.core.wifi.PeerStatus
import dev.nura.app.field.sync.core.wifi.WifiDirectPermissions
import dev.nura.app.field.sync.ui.components.BackTopAppBar
import dev.nura.app.field.sync.ui.components.ErrorSnackbarEffect
import dev.nura.app.field.sync.ui.components.MessageCard

@Composable
fun TransferScreen(
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
    viewModel: TransferViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    ErrorSnackbarEffect(state.selection.message, snackbarHostState, viewModel::dismissMessage)

    var hasPermissions by remember { mutableStateOf(WifiDirectPermissions.hasRequired(context)) }
    var needsLocation by remember { mutableStateOf(WifiDirectPermissions.needsLocationServices(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasPermissions = WifiDirectPermissions.hasRequired(context)
        needsLocation = WifiDirectPermissions.needsLocationServices(context)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasPermissions = WifiDirectPermissions.hasRequired(context)
    }

    val phase = state.phase
    val sessionBusy = phase is TransferPhase.Connecting || phase is TransferPhase.AwaitingApproval || phase is TransferPhase.InProgress

    Scaffold(
        topBar = { BackTopAppBar(title = "Device transfer", onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            when {
                !state.wifi.isSupported -> {
                    MessageCard("Wi-Fi Direct not available", "This phone does not support Wi-Fi Direct.", isError = true)
                    return@Column
                }
                !hasPermissions -> {
                    MessageCard(
                        "Permission needed",
                        "FieldSync needs the nearby-devices permission to find and connect to the other phone. " +
                            "No internet connection is used.",
                    ) {
                        Button(
                            onClick = { permissionLauncher.launch(WifiDirectPermissions.required + WifiDirectPermissions.optional) },
                            modifier = Modifier.padding(top = 8.dp),
                        ) { Text("Grant permission") }
                    }
                    return@Column
                }
                needsLocation -> MessageCard("Turn on location", "Android requires location services to find nearby devices on this phone.") {
                    TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) {
                        Text("Open location settings")
                    }
                }
                state.wifi.isActive && !state.wifi.isEnabled -> MessageCard("Turn on Wi-Fi", "Wi-Fi must be on for Wi-Fi Direct. It does not need to be connected to a network.") {
                    TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }) {
                        Text("Open Wi-Fi settings")
                    }
                }
            }

            SessionSection(phase, viewModel)

            SelectionSection(state.selection, enabled = !sessionBusy, viewModel = viewModel)
            HorizontalDivider()

            val connection = state.wifi.connection
            if (connection != null) {
                Text(
                    "Connected over Wi-Fi Direct (${if (connection.isGroupOwner) "group owner" else "client"}).",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = viewModel::disconnect, modifier = Modifier.fillMaxWidth()) { Text("Disconnect") }
            } else {
                Text(
                    "Open this screen on both phones, then tap Find nearby devices on one of them and connect. " +
                        "The transfer starts automatically once the phones are connected.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = viewModel::findDevices, enabled = !sessionBusy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.wifi.isDiscovering) "Searching… (tap to refresh)" else "Find nearby devices")
                }
                if (state.wifi.peers.isEmpty()) {
                    if (state.wifi.isDiscovering) Text("No devices found yet. Keep this screen open on the other phone.")
                } else {
                    Text("Nearby devices", style = MaterialTheme.typography.titleMedium)
                    state.wifi.peers.forEach { peer ->
                        ListItem(
                            headlineContent = { Text(peer.name) },
                            supportingContent = { Text("Status: ${peer.status.label}") },
                            trailingContent = {
                                TextButton(
                                    onClick = { viewModel.connect(peer) },
                                    enabled = !sessionBusy && peer.status == PeerStatus.AVAILABLE,
                                ) { Text("Connect") }
                            },
                        )
                    }
                }
            }
            TextButton(onClick = onOpenHistory) { Text("Sync history, trusted devices and conflicts") }
        }
    }

    (phase as? TransferPhase.AwaitingApproval)?.let { approval ->
        val peer = approval.request.peer
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Pair with ${peer.name}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This device is not trusted yet. It says it is a ${peer.role.displayName} device.")
                    Text("Check that the other phone shows exactly this code:")
                    Text(
                        approval.request.code.chunked(3).joinToString(" "),
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    Text("Only pair if the codes match and you recognise the device.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.respondToPairing(true) }) { Text("Codes match – pair") } },
            dismissButton = { TextButton(onClick = { viewModel.respondToPairing(false) }) { Text("Reject") } },
        )
    }
}

@Composable
private fun SessionSection(phase: TransferPhase, viewModel: TransferViewModel) {
    when (phase) {
        TransferPhase.Idle, is TransferPhase.AwaitingApproval -> Unit
        is TransferPhase.Connecting -> MessageCard("Connecting", phase.message) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
            TextButton(onClick = viewModel::cancelTransfer) { Text("Cancel") }
        }
        is TransferPhase.InProgress -> MessageCard("Transferring with ${phase.peerName}", phase.stage) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
            StatsText(phase.stats)
            TextButton(onClick = viewModel::cancelTransfer) { Text("Cancel") }
        }
        is TransferPhase.Completed -> MessageCard(
            "Transfer complete",
            (if (phase.newlyPaired) "${phase.peerName} is now a trusted device. " else "") + "Synced with ${phase.peerName}.",
        ) {
            StatsText(phase.stats)
            phase.stats.rejectedReasons.take(5).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = viewModel::dismissResult) { Text("Done") }
        }
        is TransferPhase.Failed -> MessageCard("Transfer failed", phase.message, isError = true) {
            Text(
                "Only batches the other phone confirmed were marked as sent; retrying resends the rest safely.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row {
                TextButton(onClick = viewModel::retry) { Text("Retry") }
                TextButton(onClick = viewModel::dismissResult) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun StatsText(stats: TransferStats) {
    Text(
        "Sent ${stats.sent} · Received ${stats.received} · Conflicts ${stats.conflicts} · Rejected ${stats.rejected}",
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun SelectionSection(selection: TransferSelection, enabled: Boolean, viewModel: TransferViewModel) {
    Text("Send from this phone", style = MaterialTheme.typography.titleMedium)
    if (!selection.canSend || selection.sendable.isEmpty()) {
        Text("You do not have permission to send data.", style = MaterialTheme.typography.bodyMedium)
    }
    selection.sendable.forEach { type ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = type in selection.selected, onCheckedChange = { viewModel.toggleType(type) }, enabled = enabled)
            Text(type.displayName)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("Accept data from the other phone", modifier = Modifier.weight(1f))
        Switch(
            checked = selection.acceptIncoming,
            onCheckedChange = viewModel::setAcceptIncoming,
            enabled = enabled && selection.canReceive,
        )
    }
}
