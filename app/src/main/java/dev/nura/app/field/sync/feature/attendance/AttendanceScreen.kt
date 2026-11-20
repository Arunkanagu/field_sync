package dev.nura.app.field.sync.feature.attendance

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nura.app.field.sync.R
import dev.nura.app.field.sync.core.util.formatForDisplay
import dev.nura.app.field.sync.domain.model.AttendanceRecord
import dev.nura.app.field.sync.domain.model.AttendanceStatus
import dev.nura.app.field.sync.domain.validation.InputParsing
import dev.nura.app.field.sync.ui.components.BackTopAppBar
import dev.nura.app.field.sync.ui.components.EmptyState
import dev.nura.app.field.sync.ui.components.ErrorSnackbarEffect
import dev.nura.app.field.sync.ui.components.FormTextField

@Composable
fun AttendanceScreen(
    onBack: () -> Unit,
    viewModel: AttendanceViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    ErrorSnackbarEffect(state.error, snackbarHostState, viewModel::dismissError)

    Scaffold(
        topBar = { BackTopAppBar(title = "Attendance", onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                IconButton(onClick = viewModel::previousDay) {
                    Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = "Previous day")
                }
                Text(
                    if (state.date == state.today) "Today · ${state.date.formatForDisplay()}" else state.date.formatForDisplay(),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).clickable(onClick = viewModel::goToToday),
                )
                IconButton(onClick = viewModel::nextDay, enabled = state.canGoForward) {
                    Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = "Next day")
                }
            }
            Text(
                "Marked ${state.markedCount}/${state.rows.size} · " +
                    AttendanceStatus.entries.joinToString(" · ") { "${it.shortLabel} ${state.count(it)}" },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            HorizontalDivider()

            if (!state.isLoading && state.rows.isEmpty()) {
                EmptyState("No active employees.")
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.rows, key = { it.employee.id }) { row ->
                        val record = row.record
                        ListItem(
                            headlineContent = { Text(row.employee.name) },
                            supportingContent = { Text(record?.describe() ?: "${row.employee.employeeCode} · Not marked") },
                            trailingContent = {
                                if (record == null) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        listOf(AttendanceStatus.PRESENT, AttendanceStatus.ABSENT).forEach { status ->
                                            FilterChip(
                                                selected = false,
                                                onClick = { viewModel.quickMark(row, status) },
                                                label = { Text(status.shortLabel) },
                                            )
                                        }
                                    }
                                } else {
                                    Text(record.status.displayName, style = MaterialTheme.typography.labelLarge)
                                }
                            },
                            modifier = Modifier.clickable { viewModel.open(row) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    state.form?.let { form -> AttendanceDialog(form, viewModel) }
}

private fun AttendanceRecord.describe(): String {
    val parts = mutableListOf<String>()
    if (inTime != null || outTime != null) {
        parts += "${InputParsing.formatTime(inTime).ifEmpty { "?" }}–${InputParsing.formatTime(outTime).ifEmpty { "?" }}"
    }
    if (overtimeMinutes > 0) parts += "OT $overtimeMinutes min"
    absentReason?.let { parts += it }
    parts += "by $updatedBy"
    return parts.joinToString(" · ")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttendanceDialog(form: AttendanceForm, viewModel: AttendanceViewModel) {
    val enabled = form.canChange && !form.isSaving
    AlertDialog(
        onDismissRequest = { if (!form.isSaving) viewModel.closeForm() },
        title = { Text(form.row.employee.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    AttendanceStatus.entries.forEach { status ->
                        FilterChip(
                            selected = form.status == status,
                            onClick = { viewModel.onFormChange { it.copy(status = status) } },
                            label = { Text(status.displayName) },
                            enabled = enabled,
                        )
                    }
                }
                if (form.status == AttendanceStatus.ABSENT) {
                    FormTextField(form.absentReason, { v -> viewModel.onFormChange { it.copy(absentReason = v) } }, "Reason (optional)", enabled = enabled)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FormTextField(
                            form.inTime,
                            { v -> viewModel.onFormChange { it.copy(inTime = v) } },
                            "In",
                            modifier = Modifier.weight(1f),
                            enabled = enabled,
                            placeholder = "09:00",
                        )
                        FormTextField(
                            form.outTime,
                            { v -> viewModel.onFormChange { it.copy(outTime = v) } },
                            "Out",
                            modifier = Modifier.weight(1f),
                            enabled = enabled,
                            placeholder = "17:30",
                        )
                    }
                    FormTextField(
                        form.overtimeMinutes,
                        { v -> viewModel.onFormChange { it.copy(overtimeMinutes = v) } },
                        "Overtime (minutes)",
                        enabled = enabled,
                        keyboardType = KeyboardType.Number,
                    )
                }
                FormTextField(form.notes, { v -> viewModel.onFormChange { it.copy(notes = v) } }, "Notes (optional)", enabled = enabled)
                if (!form.canChange) {
                    Text("Changing earlier days needs the Edit attendance permission.", style = MaterialTheme.typography.bodySmall)
                }
                form.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::save, enabled = enabled) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (form.row.record != null && form.canChange) {
                    TextButton(onClick = viewModel::clear, enabled = enabled) { Text("Clear") }
                }
                TextButton(onClick = viewModel::closeForm, enabled = !form.isSaving) { Text("Cancel") }
            }
        },
    )
}
