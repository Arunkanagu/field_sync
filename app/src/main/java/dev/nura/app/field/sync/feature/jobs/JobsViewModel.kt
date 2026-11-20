package dev.nura.app.field.sync.feature.jobs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nura.app.field.sync.core.util.runSuspendCatching
import dev.nura.app.field.sync.domain.auth.SessionManager
import dev.nura.app.field.sync.domain.model.Job
import dev.nura.app.field.sync.domain.model.JobStatus
import dev.nura.app.field.sync.domain.model.Permission
import dev.nura.app.field.sync.domain.repository.JobRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class JobFilter(val label: String, val matches: (JobStatus) -> Boolean) {
    ACTIVE("Active", { it.isActive }),
    COMPLETED("Completed", { it == JobStatus.COMPLETED }),
    CANCELLED("Cancelled", { it == JobStatus.CANCELLED }),
    ALL("All", { true }),
}

data class ProgressForm(
    val job: Job,
    val status: JobStatus,
    val note: String,
    val isSaving: Boolean = false,
    val confirmDelete: Boolean = false,
    val error: String? = null,
)

data class JobsUiState(
    val jobs: List<Job> = emptyList(),
    val filter: JobFilter = JobFilter.ACTIVE,
    val canCreate: Boolean = false,
    val isLoading: Boolean = true,
    val form: ProgressForm? = null,
    val error: String? = null,
) {
    val visible: List<Job> get() = jobs.filter { filter.matches(it.status) }

    /** Statuses the current user may pick in the progress dialog. */
    fun selectableStatuses(job: Job): List<JobStatus> = when {
        canCreate -> JobStatus.entries
        job.status == JobStatus.CANCELLED -> listOf(JobStatus.CANCELLED)
        else -> JobStatus.entries - JobStatus.CANCELLED
    }
}

@HiltViewModel
class JobsViewModel @Inject constructor(
    private val jobRepository: JobRepository,
    sessionManager: SessionManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(JobsUiState(canCreate = sessionManager.hasPermission(Permission.CREATE_JOBS)))
    val uiState: StateFlow<JobsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            runSuspendCatching {
                jobRepository.observeJobs().collect { jobs -> _uiState.update { it.copy(jobs = jobs, isLoading = false) } }
            }.onFailure { e -> _uiState.update { it.copy(isLoading = false, error = e.message) } }
        }
    }

    fun setFilter(filter: JobFilter) = _uiState.update { it.copy(filter = filter) }
    fun dismissError() = _uiState.update { it.copy(error = null) }

    fun open(job: Job) = _uiState.update { it.copy(form = ProgressForm(job, job.status, job.progressNote.orEmpty())) }
    fun closeForm() = _uiState.update { it.copy(form = null) }

    fun onFormChange(transform: (ProgressForm) -> ProgressForm) = _uiState.update { state ->
        state.copy(form = state.form?.let { transform(it).copy(error = null) })
    }

    fun saveProgress() {
        val form = _uiState.value.form ?: return
        submit { jobRepository.updateProgress(form.job.id, form.status, form.note) }
    }

    fun delete() {
        val form = _uiState.value.form ?: return
        submit { jobRepository.deleteJob(form.job.id) }
    }

    private fun submit(action: suspend () -> Unit) {
        _uiState.update { state -> state.copy(form = state.form?.copy(isSaving = true, confirmDelete = false)) }
        viewModelScope.launch {
            runSuspendCatching { action() }
                .onSuccess { closeForm() }
                .onFailure { e ->
                    _uiState.update { state -> state.copy(form = state.form?.copy(isSaving = false, error = e.message ?: "Could not save.")) }
                }
        }
    }
}
