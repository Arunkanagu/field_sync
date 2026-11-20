package dev.nura.app.field.sync.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nura.app.field.sync.core.util.runSuspendCatching
import dev.nura.app.field.sync.domain.model.LoginResult
import dev.nura.app.field.sync.domain.repository.UserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(
    val username: String = "",
    val password: String = "",
    val isSubmitting: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val userRepository: UserRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun onUsernameChange(value: String) = _uiState.update { it.copy(username = value, error = null) }
    fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value, error = null) }

    fun submit() {
        val state = _uiState.value
        if (state.isSubmitting) return
        if (state.username.isBlank() || state.password.isEmpty()) {
            _uiState.update { it.copy(error = "Enter your username and password.") }
            return
        }

        _uiState.update { it.copy(isSubmitting = true, error = null) }
        viewModelScope.launch {
            val error = runSuspendCatching { userRepository.login(state.username, state.password) }
                .fold(
                    onSuccess = { result ->
                        when (result) {
                            is LoginResult.Success -> null
                            LoginResult.InvalidCredentials -> "Incorrect username or password."
                            LoginResult.AccountDisabled -> "This account has been deactivated."
                            is LoginResult.LockedOut ->
                                "Too many failed attempts. Try again in ${result.retryAfterSeconds} s."
                        }
                    },
                    onFailure = { it.message ?: "Sign-in failed." },
                )
            _uiState.update { it.copy(isSubmitting = false, password = "", error = error) }
        }
    }
}
