package dev.nura.app.field.sync.feature.employees

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nura.app.field.sync.core.util.runSuspendCatching
import dev.nura.app.field.sync.domain.auth.SessionManager
import dev.nura.app.field.sync.domain.model.Employee
import dev.nura.app.field.sync.domain.model.Permission
import dev.nura.app.field.sync.domain.repository.EmployeeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

data class EmployeesUiState(
    val all: List<Employee> = emptyList(),
    val query: String = "",
    val canAdd: Boolean = false,
    val isLoading: Boolean = true,
    val error: String? = null,
) {
    val visible: List<Employee>
        get() {
            val q = query.trim().lowercase(Locale.ROOT)
            if (q.isEmpty()) return all
            return all.filter {
                it.name.lowercase(Locale.ROOT).contains(q) ||
                    it.employeeCode.lowercase(Locale.ROOT).contains(q) ||
                    it.mobile?.contains(q) == true
            }
        }
}

@HiltViewModel
class EmployeesViewModel @Inject constructor(
    employeeRepository: EmployeeRepository,
    sessionManager: SessionManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(EmployeesUiState(canAdd = sessionManager.hasPermission(Permission.ADD_EMPLOYEES)))
    val uiState: StateFlow<EmployeesUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            runSuspendCatching {
                employeeRepository.observeEmployees().collect { list ->
                    _uiState.update { it.copy(all = list, isLoading = false) }
                }
            }.onFailure { e -> _uiState.update { it.copy(isLoading = false, error = e.message) } }
        }
    }

    fun onQueryChange(value: String) = _uiState.update { it.copy(query = value) }
    fun dismissError() = _uiState.update { it.copy(error = null) }
}
