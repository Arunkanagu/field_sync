package dev.nura.app.field.sync.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nura.app.field.sync.ui.components.PasswordField
import dev.nura.app.field.sync.ui.components.RoleSelector
import dev.nura.app.field.sync.ui.components.UsernameField

@Composable
fun SetupScreen(viewModel: SetupViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold { innerPadding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            Text("Set up FieldSync", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Choose this device's role and create its first account. " +
                    "Accounts are stored only on this phone.",
                style = MaterialTheme.typography.bodyMedium,
            )
            RoleSelector(selected = state.role, onSelect = viewModel::onRoleSelected, enabled = !state.isSubmitting)
            UsernameField(state.username, viewModel::onUsernameChange, enabled = !state.isSubmitting)
            PasswordField(state.password, viewModel::onPasswordChange, "Password", enabled = !state.isSubmitting)
            PasswordField(
                value = state.confirmPassword,
                onValueChange = viewModel::onConfirmPasswordChange,
                label = "Confirm password",
                enabled = !state.isSubmitting,
                imeAction = ImeAction.Done,
            )
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = viewModel::submit,
                enabled = !state.isSubmitting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.isSubmitting) "Creating account…" else "Create account")
            }
        }
    }
}
