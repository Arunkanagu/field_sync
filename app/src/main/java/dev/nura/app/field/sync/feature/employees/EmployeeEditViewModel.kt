package dev.nura.app.field.sync.feature.employees

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nura.app.field.sync.core.util.formatTimestamp
import dev.nura.app.field.sync.core.util.runSuspendCatching
import dev.nura.app.field.sync.domain.auth.SessionManager
import dev.nura.app.field.sync.domain.model.EmployeeDraft
import dev.nura.app.field.sync.domain.model.EmployeeStatus
import dev.nura.app.field.sync.domain.model.Permission
import dev.nura.app.field.sync.domain.repository.EmployeeRepository
import dev.nura.app.field.sync.domain.validation.InputParsing
import dev.nura.app.field.sync.navigation.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EmployeeEditUiState(
    val isNew: Boolean,
    val canEdit: Boolean,
    val canDelete: Boolean,
    val isLoading: Boolean,
    val employeeCode: String = "",
    val name: String = "",
    val mobile: String = "",
    val address: String = "",
    val dailyWage: String = "",
    val status: EmployeeStatus = EmployeeStatus.ACTIVE,
    val lastChange: String? = null,
    val isSaving: Boolean = false,
    val confirmDelete: Boolean = false,
    val error: String? = null,
    val finished: Boolean = false,
)

@HiltViewModel
class EmployeeEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val employeeRepository: EmployeeRepository,
    sessionManager: SessionManager,
) : ViewModel() {

    private val employeeId: String? = savedStateHandle[Routes.ARG_ID]

    private val _uiState = MutableStateFlow(
        EmployeeEditUiState(
            isNew = employeeId == null,
            canEdit = sessionManager.hasPermission(if (employeeId == null) Permission.ADD_EMPLOYEES else Permission.EDIT_EMPLOYEES),
            canDelete = employeeId != null && sessionManager.hasPermission(Permission.DELETE_EMPLOYEES),
            isLoading = employeeId != null,
        ),
    )
    val uiState: StateFlow<EmployeeEditUiState> = _uiState.asStateFlow()

    init {
        if (employeeId != null) {
            viewModelScope.launch {
                runSuspendCatching { employeeRepository.observeEmployee(employeeId).first() }
                    .onSuccess { employee ->
                        if (employee == null) {
                            _uiState.update { it.copy(isLoading = false, error = "Employee not found.", finished = true) }
                        } else {
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    employeeCode = employee.employeeCode,
                                    name = employee.name,
                                    mobile = employee.mobile.orEmpty(),
                                    address = employee.address.orEmpty(),
                                    dailyWage = InputParsing.formatMoney(employee.dailyWageMinor),
                                    status = employee.status,
                                    lastChange = "Updated ${formatTimestamp(employee.updatedAt)} by ${employee.updatedBy}",
                                )
                            }
                        }
                    }
                    .onFailure { e -> _uiState.update { it.copy(isLoading = false, error = e.message) } }
            }
        }
    }

    fun onChange(transform: (EmployeeEditUiState) -> EmployeeEditUiState) = _uiState.update { transform(it).copy(error = null) }
    fun dismissError() = _uiState.update { it.copy(error = null) }
    fun requestDelete() = _uiState.update { it.copy(confirmDelete = true) }
    fun cancelDelete() = _uiState.update { it.copy(confirmDelete = false) }

    fun save() {
        val state = _uiState.value
        if (state.isSaving || !state.canEdit) return
        val draft = EmployeeDraft(
            employeeCode = state.employeeCode,
            name = state.name,
            mobile = state.mobile,
            address = state.address,
            dailyWage = state.dailyWage,
            status = state.status,
        )
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            runSuspendCatching {
                if (employeeId == null) employeeRepository.createEmployee(draft) else employeeRepository.updateEmployee(employeeId, draft)
            }
                .onSuccess { _uiState.update { it.copy(isSaving = false, finished = true) } }
                .onFailure { e -> _uiState.update { it.copy(isSaving = false, error = e.message ?: "Could not save.") } }
        }
    }

    fun delete() {
        val id = employeeId ?: return
        _uiState.update { it.copy(confirmDelete = false, isSaving = true) }
        viewModelScope.launch {
            runSuspendCatching { employeeRepository.deleteEmployee(id) }
                .onSuccess { _uiState.update { it.copy(isSaving = false, finished = true) } }
                .onFailure { e -> _uiState.update { it.copy(isSaving = false, error = e.message) } }
        }
    }
}
