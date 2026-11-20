package dev.nura.app.field.sync.feature.jobs

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nura.app.field.sync.core.util.runSuspendCatching
import dev.nura.app.field.sync.domain.model.Employee
import dev.nura.app.field.sync.domain.model.EmployeeStatus
import dev.nura.app.field.sync.domain.model.JobDraft
import dev.nura.app.field.sync.domain.repository.AttendanceRepository
import dev.nura.app.field.sync.domain.repository.EmployeeRepository
import dev.nura.app.field.sync.domain.repository.JobRepository
import dev.nura.app.field.sync.navigation.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class JobEditUiState(
    val isNew: Boolean,
    val isLoading: Boolean,
    val employees: List<Employee> = emptyList(),
    val employeeId: String? = null,
    val title: String = "",
    val description: String = "",
    val assignedDate: String = "",
    val dueDate: String = "",
    val isSaving: Boolean = false,
    val error: String? = null,
    val finished: Boolean = false,
) {
    val selectedEmployee: Employee? get() = employees.firstOrNull { it.id == employeeId }
}

@HiltViewModel
class JobEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val jobRepository: JobRepository,
    employeeRepository: EmployeeRepository,
    attendanceRepository: AttendanceRepository,
) : ViewModel() {

    private val jobId: String? = savedStateHandle[Routes.ARG_ID]

    private val _uiState = MutableStateFlow(
        JobEditUiState(isNew = jobId == null, isLoading = true, assignedDate = attendanceRepository.today().toString()),
    )
    val uiState: StateFlow<JobEditUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            runSuspendCatching {
                val employees = employeeRepository.observeEmployees().first()
                val job = jobId?.let { jobRepository.observeJob(it).first() }
                if (jobId != null && job == null) throw IllegalArgumentException("Job not found.")
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        // Keep the currently assigned employee selectable even if they were deactivated.
                        employees = employees.filter { it.status == EmployeeStatus.ACTIVE || it.id == job?.employeeId },
                        employeeId = job?.employeeId,
                        title = job?.title.orEmpty(),
                        description = job?.description.orEmpty(),
                        assignedDate = job?.assignedDate?.toString() ?: state.assignedDate,
                        dueDate = job?.dueDate?.toString().orEmpty(),
                    )
                }
            }.onFailure { e -> _uiState.update { it.copy(isLoading = false, error = e.message, finished = jobId != null) } }
        }
    }

    fun onChange(transform: (JobEditUiState) -> JobEditUiState) = _uiState.update { transform(it).copy(error = null) }
    fun dismissError() = _uiState.update { it.copy(error = null) }

    fun save() {
        val state = _uiState.value
        if (state.isSaving) return
        val draft = JobDraft(
            employeeId = state.employeeId,
            title = state.title,
            description = state.description,
            assignedDate = state.assignedDate,
            dueDate = state.dueDate,
        )
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            runSuspendCatching {
                if (jobId == null) jobRepository.createJob(draft) else jobRepository.updateJob(jobId, draft)
            }
                .onSuccess { _uiState.update { it.copy(isSaving = false, finished = true) } }
                .onFailure { e -> _uiState.update { it.copy(isSaving = false, error = e.message ?: "Could not save.") } }
        }
    }
}
