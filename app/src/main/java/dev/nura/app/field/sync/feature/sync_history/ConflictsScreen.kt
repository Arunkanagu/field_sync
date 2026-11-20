package dev.nura.app.field.sync.feature.sync_history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nura.app.field.sync.core.sync.ConflictResolver
import dev.nura.app.field.sync.core.util.formatTimestamp
import dev.nura.app.field.sync.core.util.runSuspendCatching
import dev.nura.app.field.sync.domain.model.SyncConflict
import dev.nura.app.field.sync.ui.components.BackTopAppBar
import dev.nura.app.field.sync.ui.components.EmptyState
import dev.nura.app.field.sync.ui.components.ErrorSnackbarEffect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConflictsViewModel @Inject constructor(
    private val conflictResolver: ConflictResolver,
) : ViewModel() {

    val conflicts: StateFlow<List<SyncConflict>?> = conflictResolver.observeOpenConflicts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun resolve(conflict: SyncConflict, keepLocal: Boolean) {
        viewModelScope.launch {
            runSuspendCatching { conflictResolver.resolve(conflict.id, keepLocal) }
                .onFailure { e -> _error.update { e.message } }
        }
    }

    fun dismissError() = _error.update { null }
}

@Composable
fun ConflictsScreen(
    onBack: () -> Unit,
    viewModel: ConflictsViewModel = hiltViewModel(),
) {
    val conflicts by viewModel.conflicts.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    ErrorSnackbarEffect(error, snackbarHostState, viewModel::dismissError)

    Scaffold(
        topBar = { BackTopAppBar(title = "Sync conflicts", onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val list = conflicts
        if (list != null && list.isEmpty()) {
            EmptyState("No conflicts to review.", Modifier.padding(innerPadding))
            return@Scaffold
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp),
        ) {
            item {
                Text(
                    "Both phones changed these records while offline. Choose which version to keep; " +
                        "your choice is sent to the other phone on the next transfer.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            items(list.orEmpty(), key = { it.id }) { conflict ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(conflict.title, style = MaterialTheme.typography.titleSmall)
                        Text("Detected ${formatTimestamp(conflict.detectedAt)}", style = MaterialTheme.typography.bodySmall)
                        Text("This phone: ${conflict.localSummary}", style = MaterialTheme.typography.bodyMedium)
                        Text("${conflict.remoteDeviceName}: ${conflict.remoteSummary}", style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            OutlinedButton(onClick = { viewModel.resolve(conflict, keepLocal = true) }) { Text("Keep mine") }
                            OutlinedButton(onClick = { viewModel.resolve(conflict, keepLocal = false) }) { Text("Use theirs") }
                        }
                    }
                }
            }
        }
    }
}
