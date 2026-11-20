package dev.nura.app.field.sync.feature.employees

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nura.app.field.sync.R
import dev.nura.app.field.sync.domain.model.EmployeeStatus
import dev.nura.app.field.sync.ui.components.BackTopAppBar
import dev.nura.app.field.sync.ui.components.EmptyState
import dev.nura.app.field.sync.ui.components.ErrorSnackbarEffect

@Composable
fun EmployeesScreen(
    onBack: () -> Unit,
    onOpenEmployee: (id: String) -> Unit,
    onAddEmployee: () -> Unit,
    viewModel: EmployeesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    ErrorSnackbarEffect(state.error, snackbarHostState, viewModel::dismissError)

    Scaffold(
        topBar = { BackTopAppBar(title = "Employees", onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (state.canAdd) {
                FloatingActionButton(onClick = onAddEmployee) {
                    Icon(painterResource(R.drawable.ic_add), contentDescription = "Add employee")
                }
            }
        },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                label = { Text("Search name, code or mobile") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            val employees = state.visible
            if (!state.isLoading && employees.isEmpty()) {
                EmptyState(
                    when {
                        state.query.isNotBlank() -> "No employees match your search."
                        state.canAdd -> "No employees yet. Tap + to add one."
                        else -> "No employees yet. They arrive when the Admin device sends data."
                    },
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(employees, key = { it.id }) { employee ->
                        ListItem(
                            headlineContent = { Text(employee.name) },
                            supportingContent = {
                                Text(listOfNotNull(employee.employeeCode, employee.mobile).joinToString(" · "))
                            },
                            trailingContent = {
                                if (employee.status == EmployeeStatus.INACTIVE) Text(employee.status.displayName)
                            },
                            modifier = Modifier.clickable { onOpenEmployee(employee.id) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
