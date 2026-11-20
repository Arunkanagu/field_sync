package dev.nura.app.field.sync.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nura.app.field.sync.core.device.DeviceSettingsRepository
import dev.nura.app.field.sync.core.util.runSuspendCatching
import dev.nura.app.field.sync.domain.model.Role
import dev.nura.app.field.sync.domain.repository.UserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SetupUiState(
    val role: Role? = null,
    val username: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val isSubmitting: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val deviceSettings: DeviceSettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    fun onRoleSelected(role: Role) = _uiState.update { it.copy(role = role, error = null) }
    fun onUsernameChange(value: String) = _uiState.update { it.copy(username = value, error = null) }
    fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value, error = null) }
    fun onConfirmPasswordChange(value: String) = _uiState.update { it.copy(confirmPassword = value, error = null) }

    fun submit() {
        val state = _uiState.value
        if (state.isSubmitting) return
        val role = state.role
        val error = when {
            role == null -> "Choose whether this is the Admin or Manager device."
            state.password != state.confirmPassword -> "Passwords do not match."
            else -> null
        }
        if (error != null || role == null) {
            _uiState.update { it.copy(error = error) }
            return
        }

        _uiState.update { it.copy(isSubmitting = true, error = null) }
        viewModelScope.launch {
            runSuspendCatching {
                deviceSettings.getOrCreateDeviceId()
                userRepository.createInitialAccount(state.username, state.password, role)
                deviceSettings.setDeviceRole(role)
            }.onFailure { e ->
                _uiState.update { it.copy(isSubmitting = false, error = e.message ?: "Setup failed.") }
            }
            // On success the root state switches to the signed-in graph.
        }
    }
}
