package dev.nura.app.field.sync.feature.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nura.app.field.sync.R
import dev.nura.app.field.sync.domain.model.JobStatus
import dev.nura.app.field.sync.domain.validation.InputParsing
import dev.nura.app.field.sync.ui.components.BackTopAppBar
import java.time.format.DateTimeFormatter

private val monthFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")

@Composable
fun ReportsScreen(
    onBack: () -> Unit,
    viewModel: ReportsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(topBar = { BackTopAppBar(title = "Reports", onBack = onBack) }) { innerPadding ->
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
        ) {
            item {
                Text("Jobs", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                Text(
                    JobStatus.entries.joinToString(" · ") { "${it.displayName} ${state.jobCounts[it] ?: 0}" },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Attendance", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = viewModel::previousMonth) {
                        Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = "Previous month")
                    }
                    Text(state.month.format(monthFormatter))
                    IconButton(onClick = viewModel::nextMonth, enabled = state.month < state.currentMonth) {
                        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = "Next month")
                    }
                }
                if (state.totalWagesMinor > 0) {
                    Text(
                        "Estimated wages: ${InputParsing.formatMoney(state.totalWagesMinor)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            items(state.rows, key = { it.employee.id }) { row ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${row.employee.name} (${row.employee.employeeCode})", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Present ${row.presentDays} · Half ${row.halfDays} · Absent ${row.absentDays} · Leave ${row.leaveDays}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        val overtime = if (row.overtimeMinutes > 0) "Overtime ${row.overtimeMinutes / 60}h ${row.overtimeMinutes % 60}m" else null
                        val wages = row.estimatedWagesMinor?.let { "Wages ≈ ${InputParsing.formatMoney(it)}" }
                        listOfNotNull(overtime, wages).takeIf { it.isNotEmpty() }?.let {
                            Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
