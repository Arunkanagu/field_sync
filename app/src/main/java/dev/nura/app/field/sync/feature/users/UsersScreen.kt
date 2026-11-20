package dev.nura.app.field.sync.feature.users

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nura.app.field.sync.R
import dev.nura.app.field.sync.ui.components.BackTopAppBar
import dev.nura.app.field.sync.ui.components.PasswordField
import dev.nura.app.field.sync.ui.components.RoleSelector
import dev.nura.app.field.sync.ui.components.UsernameField

@Composable
fun UsersScreen(
    onBack: () -> Unit,
    viewModel: UsersViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    Scaffold(
        topBar = { BackTopAppBar(title = "Local users", onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = viewModel::showAddUser) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = "Add user")
            }
        },
    ) { innerPadding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            items(state.users, key = { it.id }) { user ->
                val isSelf = user.id == state.currentUserId
                ListItem(
                    headlineContent = { Text(if (isSelf) "${user.username} (you)" else user.username) },
                    supportingContent = {
                        Text("${user.role.displayName} · ${if (user.isActive) "Active" else "Deactivated"}")
                    },
                    trailingContent = {
                        Switch(
                            checked = user.isActive,
                            onCheckedChange = { viewModel.setActive(user, it) },
                            enabled = !isSelf,
                        )
                    },
                )
                HorizontalDivider()
            }
        }
    }

    state.addUserForm?.let { form ->
        AlertDialog(
            onDismissRequest = { if (!form.isSubmitting) viewModel.dismissAddUser() },
            title = { Text("Add user") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    RoleSelector(
                        selected = form.role,
                        onSelect = { role -> viewModel.onFormChange { it.copy(role = role) } },
                        enabled = !form.isSubmitting,
                    )
                    UsernameField(
                        value = form.username,
                        onValueChange = { value -> viewModel.onFormChange { it.copy(username = value) } },
                        enabled = !form.isSubmitting,
                    )
                    PasswordField(
                        value = form.password,
                        onValueChange = { value -> viewModel.onFormChange { it.copy(password = value) } },
                        label = "Initial password",
                        enabled = !form.isSubmitting,
                        imeAction = ImeAction.Done,
                    )
                    form.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::submitAddUser, enabled = !form.isSubmitting) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissAddUser, enabled = !form.isSubmitting) { Text("Cancel") }
            },
        )
    }
}
