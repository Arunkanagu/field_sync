package dev.nura.app.field.sync.feature.audit

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nura.app.field.sync.core.util.formatTimestamp
import dev.nura.app.field.sync.data.local.AuditLogger
import dev.nura.app.field.sync.domain.model.AuditEntry
import dev.nura.app.field.sync.ui.components.BackTopAppBar
import dev.nura.app.field.sync.ui.components.EmptyState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.stateIn
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class AuditLogViewModel @Inject constructor(auditLogger: AuditLogger) : ViewModel() {
    val entries: StateFlow<List<AuditEntry>?> =
        runCatching { auditLogger.observeRecent() }.getOrElse { emptyFlow() }
            .catch { emit(emptyList()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun AuditLogScreen(
    onBack: () -> Unit,
    viewModel: AuditLogViewModel = hiltViewModel(),
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()

    Scaffold(topBar = { BackTopAppBar(title = "Audit log", onBack = onBack) }) { innerPadding ->
        val list = entries
        if (list != null && list.isEmpty()) {
            EmptyState("No audit entries yet.", Modifier.padding(innerPadding))
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(innerPadding)) {
            items(list.orEmpty(), key = { it.id }) { entry ->
                ListItem(
                    headlineContent = {
                        Text(entry.action.replace('_', ' ').lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) })
                    },
                    supportingContent = {
                        Text(listOfNotNull("${formatTimestamp(entry.timestamp)} · ${entry.actor}", entry.details).joinToString("\n"))
                    },
                )
                HorizontalDivider()
            }
        }
    }
}
