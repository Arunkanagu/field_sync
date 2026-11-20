package dev.nura.app.field.sync.feature.sync_history

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nura.app.field.sync.core.util.formatTimestamp
import dev.nura.app.field.sync.domain.model.TransferStatus
import dev.nura.app.field.sync.domain.model.TrustStatus
import dev.nura.app.field.sync.ui.components.BackTopAppBar
import dev.nura.app.field.sync.ui.components.ErrorSnackbarEffect

@Composable
fun SyncHistoryScreen(
    onBack: () -> Unit,
    onOpenConflicts: () -> Unit,
    viewModel: SyncHistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    ErrorSnackbarEffect(state.error, snackbarHostState, viewModel::dismissError)

    Scaffold(
        topBar = { BackTopAppBar(title = "Sync history", onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        LazyColumn(Modifier.fillMaxSize().padding(innerPadding)) {
            if (state.openConflicts > 0) {
                item {
                    FilledTonalButton(onClick = onOpenConflicts, modifier = Modifier.padding(16.dp)) {
                        Text("Review ${state.openConflicts} conflict(s)")
                    }
                }
            }
            item { SectionTitle("Trusted devices") }
            if (state.devices.isEmpty()) {
                item { Text("No paired devices yet.", Modifier.padding(horizontal = 16.dp)) }
            }
            items(state.devices, key = { "device-${it.deviceId}" }) { device ->
                val revoked = device.status == TrustStatus.REVOKED
                ListItem(
                    headlineContent = { Text(device.displayName) },
                    supportingContent = {
                        Text(
                            "${device.role.displayName} · " + when {
                                revoked -> "Revoked"
                                device.lastSyncAt != null -> "Last sync ${formatTimestamp(device.lastSyncAt)}"
                                else -> "Paired ${formatTimestamp(device.pairedAt)}"
                            },
                        )
                    },
                    trailingContent = {
                        if (!revoked && state.canRevoke) {
                            TextButton(onClick = { viewModel.requestRevoke(device) }) { Text("Revoke") }
                        }
                    },
                )
            }
            item { SectionTitle("Transfers") }
            if (state.history.isEmpty()) {
                item { Text("No transfers yet.", Modifier.padding(horizontal = 16.dp)) }
            }
            items(state.history, key = { "transfer-${it.id}" }) { record ->
                ListItem(
                    headlineContent = { Text("${record.peerName} · ${record.status.displayName}") },
                    supportingContent = {
                        val counts = "Sent ${record.sentCount} · Received ${record.receivedCount}" +
                            (if (record.conflictCount > 0) " · Conflicts ${record.conflictCount}" else "") +
                            (if (record.rejectedCount > 0) " · Rejected ${record.rejectedCount}" else "")
                        val error = record.errorMessage?.takeIf { record.status != TransferStatus.COMPLETED }
                        Text(
                            listOfNotNull(
                                "${formatTimestamp(record.startedAt)} by ${record.initiatedBy}",
                                counts,
                                error,
                            ).joinToString("\n"),
                        )
                    },
                )
                HorizontalDivider()
            }
        }
    }

    state.confirmRevoke?.let { device ->
        AlertDialog(
            onDismissRequest = viewModel::cancelRevoke,
            title = { Text("Revoke ${device.displayName}?") },
            text = { Text("Transfers from this device will be rejected until it is paired again (both users must confirm a new code).") },
            confirmButton = { TextButton(onClick = viewModel::revoke) { Text("Revoke") } },
            dismissButton = { TextButton(onClick = viewModel::cancelRevoke) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
}
