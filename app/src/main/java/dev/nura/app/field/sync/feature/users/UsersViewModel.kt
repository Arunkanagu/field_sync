package dev.nura.app.field.sync.feature.users

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nura.app.field.sync.core.util.runSuspendCatching
import dev.nura.app.field.sync.domain.auth.SessionManager
import dev.nura.app.field.sync.domain.model.Role
import dev.nura.app.field.sync.domain.model.User
import dev.nura.app.field.sync.domain.repository.UserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AddUserForm(
    val username: String = "",
    val password: String = "",
    val role: Role = Role.MANAGER,
    val isSubmitting: Boolean = false,
    val error: String? = null,
)

data class UsersUiState(
    val users: List<User> = emptyList(),
    val currentUserId: String? = null,
    val addUserForm: AddUserForm? = null,
    val error: String? = null,
)

@HiltViewModel
class UsersViewModel @Inject constructor(
    private val userRepository: UserRepository,
    sessionManager: SessionManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(UsersUiState(currentUserId = sessionManager.currentUser.value?.id))
    val uiState: StateFlow<UsersUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            runSuspendCatching {
                userRepository.observeUsers().collect { users -> _uiState.update { it.copy(users = users) } }
            }.onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun showAddUser() = _uiState.update { it.copy(addUserForm = AddUserForm()) }
    fun dismissAddUser() = _uiState.update { it.copy(addUserForm = null) }
    fun dismissError() = _uiState.update { it.copy(error = null) }

    fun onFormChange(transform: (AddUserForm) -> AddUserForm) = _uiState.update { state ->
        state.copy(addUserForm = state.addUserForm?.let { transform(it).copy(error = null) })
    }

    fun submitAddUser() {
        val form = _uiState.value.addUserForm ?: return
        if (form.isSubmitting) return
        updateForm { it.copy(isSubmitting = true, error = null) }
        viewModelScope.launch {
            runSuspendCatching { userRepository.createUser(form.username, form.password, form.role) }
                .onSuccess { dismissAddUser() }
                .onFailure { e -> updateForm { it.copy(isSubmitting = false, error = e.message ?: "Could not add user.") } }
        }
    }

    fun setActive(user: User, active: Boolean) {
        viewModelScope.launch {
            runSuspendCatching { userRepository.setUserActive(user.id, active) }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    private fun updateForm(transform: (AddUserForm) -> AddUserForm) = _uiState.update { state ->
        state.copy(addUserForm = state.addUserForm?.let(transform))
    }
}
