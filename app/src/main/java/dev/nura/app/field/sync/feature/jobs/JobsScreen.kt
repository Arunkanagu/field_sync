package dev.nura.app.field.sync.feature.jobs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nura.app.field.sync.R
import dev.nura.app.field.sync.core.util.formatForDisplay
import dev.nura.app.field.sync.domain.model.JobStatus
import dev.nura.app.field.sync.ui.components.BackTopAppBar
import dev.nura.app.field.sync.ui.components.EmptyState
import dev.nura.app.field.sync.ui.components.ErrorSnackbarEffect
import dev.nura.app.field.sync.ui.components.FormTextField

@Composable
fun JobsScreen(
    onBack: () -> Unit,
    onCreateJob: () -> Unit,
    onEditJob: (id: String) -> Unit,
    viewModel: JobsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    ErrorSnackbarEffect(state.error, snackbarHostState, viewModel::dismissError)

    Scaffold(
        topBar = { BackTopAppBar(title = "Jobs", onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (state.canCreate) {
                FloatingActionButton(onClick = onCreateJob) {
                    Icon(painterResource(R.drawable.ic_add), contentDescription = "Assign job")
                }
            }
        },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            ) {
                JobFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = state.filter == filter,
                        onClick = { viewModel.setFilter(filter) },
                        label = { Text(filter.label) },
                    )
                }
            }
            val jobs = state.visible
            if (!state.isLoading && jobs.isEmpty()) {
                EmptyState(if (state.canCreate) "No jobs here. Tap + to assign one." else "No jobs here.")
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(jobs, key = { it.id }) { job ->
                        ListItem(
                            headlineContent = { Text(job.title) },
                            supportingContent = {
                                val due = job.dueDate?.let { " · due ${it.formatForDisplay()}" }.orEmpty()
                                Text("${job.employeeName ?: "Unknown employee"} · ${job.assignedDate.formatForDisplay()}$due")
                            },
                            trailingContent = { Text(job.status.displayName, style = MaterialTheme.typography.labelLarge) },
                            modifier = Modifier.clickable { viewModel.open(job) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    state.form?.let { form ->
        ProgressDialog(
            form = form,
            statuses = state.selectableStatuses(form.job),
            canEditDetails = state.canCreate,
            viewModel = viewModel,
            onEditDetails = {
                viewModel.closeForm()
                onEditJob(form.job.id)
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProgressDialog(
    form: ProgressForm,
    statuses: List<JobStatus>,
    canEditDetails: Boolean,
    viewModel: JobsViewModel,
    onEditDetails: () -> Unit,
) {
    val enabled = !form.isSaving
    if (form.confirmDelete) {
        AlertDialog(
            onDismissRequest = { viewModel.onFormChange { it.copy(confirmDelete = false) } },
            title = { Text("Delete \"${form.job.title}\"?") },
            text = { Text("The job is removed here and on paired devices after the next transfer.") },
            confirmButton = { TextButton(onClick = viewModel::delete) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { viewModel.onFormChange { it.copy(confirmDelete = false) } }) { Text("Cancel") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = { if (enabled) viewModel.closeForm() },
        title = { Text(form.job.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${form.job.employeeName ?: "Unknown employee"} · assigned by ${form.job.createdBy}")
                if (form.job.description.isNotBlank()) Text(form.job.description, style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    statuses.forEach { status ->
                        FilterChip(
                            selected = form.status == status,
                            onClick = { viewModel.onFormChange { it.copy(status = status) } },
                            label = { Text(status.displayName) },
                            enabled = enabled,
                        )
                    }
                }
                FormTextField(form.note, { v -> viewModel.onFormChange { it.copy(note = v) } }, "Progress note", enabled = enabled)
                Text("Last update by ${form.job.progressUpdatedBy}", style = MaterialTheme.typography.bodySmall)
                if (canEditDetails) {
                    Row {
                        TextButton(onClick = onEditDetails, enabled = enabled) { Text("Edit details") }
                        TextButton(onClick = { viewModel.onFormChange { it.copy(confirmDelete = true) } }, enabled = enabled) {
                            Text("Delete")
                        }
                    }
                }
                form.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::saveProgress, enabled = enabled) { Text("Save") } },
        dismissButton = { TextButton(onClick = viewModel::closeForm, enabled = enabled) { Text("Cancel") } },
    )
}
