package dev.nura.app.field.sync.feature.jobs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nura.app.field.sync.ui.components.BackTopAppBar
import dev.nura.app.field.sync.ui.components.ErrorSnackbarEffect
import dev.nura.app.field.sync.ui.components.FormTextField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobEditScreen(
    onDone: () -> Unit,
    viewModel: JobEditViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    ErrorSnackbarEffect(state.error, snackbarHostState, viewModel::dismissError)
    LaunchedEffect(state.finished) { if (state.finished) onDone() }

    Scaffold(
        topBar = { BackTopAppBar(title = if (state.isNew) "Assign job" else "Edit job", onBack = onDone) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        if (state.isLoading) {
            CircularProgressIndicator(Modifier.padding(innerPadding).padding(32.dp))
            return@Scaffold
        }
        val enabled = !state.isSaving
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it && enabled }) {
                OutlinedTextField(
                    value = state.selectedEmployee?.let { "${it.name} (${it.employeeCode})" }.orEmpty(),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Employee") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    if (state.employees.isEmpty()) {
                        DropdownMenuItem(text = { Text("No active employees") }, onClick = { expanded = false }, enabled = false)
                    }
                    state.employees.forEach { employee ->
                        DropdownMenuItem(
                            text = { Text("${employee.name} (${employee.employeeCode})") },
                            onClick = {
                                viewModel.onChange { it.copy(employeeId = employee.id) }
                                expanded = false
                            },
                        )
                    }
                }
            }
            FormTextField(state.title, { v -> viewModel.onChange { it.copy(title = v) } }, "Title", enabled = enabled)
            FormTextField(
                state.description,
                { v -> viewModel.onChange { it.copy(description = v) } },
                "Description (optional)",
                enabled = enabled,
                singleLine = false,
            )
            FormTextField(
                state.assignedDate,
                { v -> viewModel.onChange { it.copy(assignedDate = v) } },
                "Assigned date",
                enabled = enabled,
                placeholder = "2026-01-31",
            )
            FormTextField(
                state.dueDate,
                { v -> viewModel.onChange { it.copy(dueDate = v) } },
                "Due date (optional)",
                enabled = enabled,
                placeholder = "2026-02-15",
            )
            Button(onClick = viewModel::save, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.isNew) "Assign job" else "Save changes")
            }
        }
    }
}
