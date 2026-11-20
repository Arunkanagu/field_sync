package dev.nura.app.field.sync.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nura.app.field.sync.core.device.DeviceSettingsRepository
import dev.nura.app.field.sync.core.util.runSuspendCatching
import dev.nura.app.field.sync.domain.model.Role
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val deviceId: String = "",
    val deviceRole: Role? = null,
    val savedDeviceName: String = "",
    val deviceNameDraft: String = "",
    val message: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val deviceSettings: DeviceSettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val deviceId = deviceSettings.getOrCreateDeviceId()
            _uiState.update {
                it.copy(deviceId = deviceId, deviceNameDraft = deviceSettings.deviceName.first())
            }
            combine(deviceSettings.deviceName, deviceSettings.deviceRole, ::Pair).collect { (name, role) ->
                _uiState.update { it.copy(savedDeviceName = name, deviceRole = role) }
            }
        }
    }

    fun onDeviceNameChange(value: String) = _uiState.update { it.copy(deviceNameDraft = value, message = null) }

    fun saveDeviceName() {
        val draft = _uiState.value.deviceNameDraft
        viewModelScope.launch {
            val message = runSuspendCatching { deviceSettings.setDeviceName(draft) }
                .fold(onSuccess = { "Device name saved." }, onFailure = { it.message })
            _uiState.update { it.copy(message = message) }
        }
    }
}
