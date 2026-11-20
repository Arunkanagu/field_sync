package dev.nura.app.field.sync.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nura.app.field.sync.core.device.DeviceSettingsRepository
import dev.nura.app.field.sync.ui.components.BackTopAppBar

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(topBar = { BackTopAppBar(title = "Settings", onBack = onBack) }) { innerPadding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text("Device", style = MaterialTheme.typography.titleMedium)
            LabeledValue("Device role", state.deviceRole?.displayName ?: "Not set")
            LabeledValue("Device ID", state.deviceId)

            OutlinedTextField(
                value = state.deviceNameDraft,
                onValueChange = viewModel::onDeviceNameChange,
                label = { Text("Device name (shown to nearby devices)") },
                singleLine = true,
                supportingText = {
                    Text("${state.deviceNameDraft.length}/${DeviceSettingsRepository.MAX_DEVICE_NAME_LENGTH}")
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = viewModel::saveDeviceName,
                enabled = state.deviceNameDraft.trim() != state.savedDeviceName,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save device name")
            }
            state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodyLarge) }
    }
}
