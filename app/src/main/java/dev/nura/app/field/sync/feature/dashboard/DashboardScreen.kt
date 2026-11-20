package dev.nura.app.field.sync.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nura.app.field.sync.navigation.Routes
import dev.nura.app.field.sync.ui.components.MessageCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNavigate: (route: String) -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val user = state.user ?: return

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${user.role.displayName} dashboard") },
                actions = { TextButton(onClick = viewModel::logout) { Text("Log out") } },
            )
        },
    ) { innerPadding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text("Signed in as ${user.username}", style = MaterialTheme.typography.titleMedium)
            Text(
                "${state.deviceName} · ID ${state.deviceId?.take(8) ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
            )

            if (state.openConflicts > 0) {
                MessageCard(
                    "Sync conflicts need review",
                    "${state.openConflicts} record(s) were changed on both phones while offline.",
                ) {
                    TextButton(onClick = { onNavigate(Routes.CONFLICTS) }) { Text("Review") }
                }
            }

            SummaryGrid(items = state.summary, onNavigate = onNavigate)

            Text("Quick actions", style = MaterialTheme.typography.titleMedium)
            state.quickActions.forEach { action ->
                FilledTonalButton(
                    onClick = { onNavigate(action.route) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(action.label)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryGrid(items: List<SummaryItem>, onNavigate: (String) -> Unit) {
    FlowRow(
        maxItemsInEachRow = 2,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items.forEach { item ->
            Card(
                onClick = { item.route?.let(onNavigate) },
                enabled = item.route != null,
                modifier = Modifier.weight(1f),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(item.value, style = MaterialTheme.typography.headlineSmall)
                    Text(item.label, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
