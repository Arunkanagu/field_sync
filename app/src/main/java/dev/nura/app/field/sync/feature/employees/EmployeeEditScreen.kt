package dev.nura.app.field.sync.feature.employees

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nura.app.field.sync.domain.model.EmployeeStatus
import dev.nura.app.field.sync.ui.components.BackTopAppBar
import dev.nura.app.field.sync.ui.components.ErrorSnackbarEffect
import dev.nura.app.field.sync.ui.components.FormTextField

@Composable
fun EmployeeEditScreen(
    onDone: () -> Unit,
    viewModel: EmployeeEditViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    ErrorSnackbarEffect(state.error, snackbarHostState, viewModel::dismissError)
    LaunchedEffect(state.finished) { if (state.finished) onDone() }

    val title = when {
        state.isNew -> "Add employee"
        state.canEdit -> "Edit employee"
        else -> "Employee"
    }
    Scaffold(
        topBar = { BackTopAppBar(title = title, onBack = onDone) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        if (state.isLoading) {
            CircularProgressIndicator(Modifier.padding(innerPadding).padding(32.dp))
            return@Scaffold
        }
        val enabled = state.canEdit && !state.isSaving
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            FormTextField(state.employeeCode, { v -> viewModel.onChange { it.copy(employeeCode = v) } }, "Employee code", enabled = enabled)
            FormTextField(state.name, { v -> viewModel.onChange { it.copy(name = v) } }, "Name", enabled = enabled)
            FormTextField(
                state.mobile,
                { v -> viewModel.onChange { it.copy(mobile = v) } },
                "Mobile (optional)",
                enabled = enabled,
                keyboardType = KeyboardType.Phone,
            )
            FormTextField(
                state.address,
                { v -> viewModel.onChange { it.copy(address = v) } },
                "Address (optional)",
                enabled = enabled,
                singleLine = false,
            )
            FormTextField(
                state.dailyWage,
                { v -> viewModel.onChange { it.copy(dailyWage = v) } },
                "Daily wage (optional)",
                enabled = enabled,
                keyboardType = KeyboardType.Decimal,
                placeholder = "650.00",
            )

            Text("Status", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                EmployeeStatus.entries.forEachIndexed { index, status ->
                    SegmentedButton(
                        selected = state.status == status,
                        onClick = { viewModel.onChange { it.copy(status = status) } },
                        enabled = enabled,
                        shape = SegmentedButtonDefaults.itemShape(index, EmployeeStatus.entries.size),
                    ) { Text(status.displayName) }
                }
            }
            state.lastChange?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            if (state.canEdit) {
                Button(onClick = viewModel::save, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.isNew) "Add employee" else "Save changes")
                }
            } else {
                Text(
                    "Employee master data is managed on the Admin device.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (state.canDelete) {
                OutlinedButton(onClick = viewModel::requestDelete, enabled = !state.isSaving, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete employee")
                }
            }
        }
    }

    if (state.confirmDelete) {
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            title = { Text("Delete ${state.name}?") },
            text = {
                Text(
                    "The employee is removed from lists on this device and on paired devices after the next " +
                        "transfer. Existing attendance history is kept. To keep the employee visible, set them to Inactive instead.",
                )
            },
            confirmButton = { TextButton(onClick = viewModel::delete) { Text("Delete") } },
            dismissButton = { TextButton(onClick = viewModel::cancelDelete) { Text("Cancel") } },
        )
    }
}
