package dev.nura.app.field.sync.feature.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nura.app.field.sync.domain.model.EmployeeAttendanceReport
import dev.nura.app.field.sync.domain.model.JobStatus
import dev.nura.app.field.sync.domain.repository.AttendanceRepository
import dev.nura.app.field.sync.domain.repository.JobRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import java.time.YearMonth
import javax.inject.Inject

data class ReportsUiState(
    val month: YearMonth,
    val currentMonth: YearMonth,
    val rows: List<EmployeeAttendanceReport> = emptyList(),
    val jobCounts: Map<JobStatus, Int> = emptyMap(),
    val error: String? = null,
) {
    val totalWagesMinor: Long get() = rows.sumOf { it.estimatedWagesMinor ?: 0L }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReportsViewModel @Inject constructor(
    attendanceRepository: AttendanceRepository,
    jobRepository: JobRepository,
) : ViewModel() {

    private val currentMonth = YearMonth.from(attendanceRepository.today())
    private val month = MutableStateFlow(currentMonth)
    private val _uiState = MutableStateFlow(ReportsUiState(month = currentMonth, currentMonth = currentMonth))
    val uiState: StateFlow<ReportsUiState> = _uiState.asStateFlow()

    init {
        month.flatMapLatest { selected ->
            combine(
                attendanceRepository.observeReport(selected.atDay(1), selected.atEndOfMonth()),
                jobRepository.observeJobs(),
            ) { rows, jobs ->
                _uiState.value.copy(
                    month = selected,
                    rows = rows,
                    jobCounts = jobs.groupingBy { it.status }.eachCount(),
                )
            }
        }
            .onEach { _uiState.value = it }
            .catch { e -> _uiState.update { it.copy(error = e.message) } }
            .launchIn(viewModelScope)
    }

    fun previousMonth() = month.update { it.minusMonths(1) }
    fun nextMonth() = month.update { if (it < currentMonth) it.plusMonths(1) else it }
}
