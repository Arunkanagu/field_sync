package dev.nura.app.field.sync.feature.attendance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nura.app.field.sync.core.util.runSuspendCatching
import dev.nura.app.field.sync.domain.auth.SessionManager
import dev.nura.app.field.sync.domain.model.AttendanceDraft
import dev.nura.app.field.sync.domain.model.AttendanceStatus
import dev.nura.app.field.sync.domain.model.DailyAttendanceRow
import dev.nura.app.field.sync.domain.model.Permission
import dev.nura.app.field.sync.domain.repository.AttendanceRepository
import dev.nura.app.field.sync.domain.validation.InputParsing
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class AttendanceForm(
    val row: DailyAttendanceRow,
    val status: AttendanceStatus,
    val inTime: String,
    val outTime: String,
    val overtimeMinutes: String,
    val absentReason: String,
    val notes: String,
    /** False when the user may view but not change this entry (an earlier day without EDIT_ATTENDANCE). */
    val canChange: Boolean,
    val isSaving: Boolean = false,
    val error: String? = null,
)

data class AttendanceUiState(
    val date: LocalDate,
    val today: LocalDate,
    val rows: List<DailyAttendanceRow> = emptyList(),
    val isLoading: Boolean = true,
    val form: AttendanceForm? = null,
    val error: String? = null,
) {
    val canGoForward: Boolean get() = date < today
    val markedCount: Int get() = rows.count { it.record != null }
    fun count(status: AttendanceStatus) = rows.count { it.record?.status == status }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AttendanceViewModel @Inject constructor(
    private val attendanceRepository: AttendanceRepository,
    private val sessionManager: SessionManager,
) : ViewModel() {

    private val today = attendanceRepository.today()
    private val date = MutableStateFlow(today)
    private val _uiState = MutableStateFlow(AttendanceUiState(date = today, today = today))
    val uiState: StateFlow<AttendanceUiState> = _uiState.asStateFlow()

    init {
        date.flatMapLatest { day ->
            _uiState.update { it.copy(date = day, isLoading = true) }
            attendanceRepository.observeDay(day)
        }
            .onEach { rows -> _uiState.update { it.copy(rows = rows, isLoading = false) } }
            .catch { e -> _uiState.update { it.copy(isLoading = false, error = e.message) } }
            .launchIn(viewModelScope)
    }

    fun previousDay() = date.update { it.minusDays(1) }
    fun nextDay() = date.update { if (it < today) it.plusDays(1) else it }
    fun goToToday() = date.update { today }
    fun dismissError() = _uiState.update { it.copy(error = null) }

    fun open(row: DailyAttendanceRow) {
        val record = row.record
        val day = date.value
        val canChange = record == null || day == today || sessionManager.hasPermission(Permission.EDIT_ATTENDANCE)
        _uiState.update {
            it.copy(
                form = AttendanceForm(
                    row = row,
                    status = record?.status ?: AttendanceStatus.PRESENT,
                    inTime = InputParsing.formatTime(record?.inTime),
                    outTime = InputParsing.formatTime(record?.outTime),
                    overtimeMinutes = record?.overtimeMinutes?.takeIf { m -> m > 0 }?.toString().orEmpty(),
                    absentReason = record?.absentReason.orEmpty(),
                    notes = record?.notes.orEmpty(),
                    canChange = canChange,
                ),
            )
        }
    }

    fun closeForm() = _uiState.update { it.copy(form = null) }

    fun onFormChange(transform: (AttendanceForm) -> AttendanceForm) = _uiState.update { state ->
        state.copy(form = state.form?.let { transform(it).copy(error = null) })
    }

    /** One-tap marking from the list, without opening the form. */
    fun quickMark(row: DailyAttendanceRow, status: AttendanceStatus) {
        if (row.record != null) return open(row)
        val day = date.value
        viewModelScope.launch {
            runSuspendCatching { attendanceRepository.mark(row.employee.id, day, AttendanceDraft(status)) }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun save() {
        val form = _uiState.value.form ?: return
        if (form.isSaving) return
        val draft = AttendanceDraft(
            status = form.status,
            inTime = form.inTime,
            outTime = form.outTime,
            overtimeMinutes = form.overtimeMinutes,
            absentReason = form.absentReason,
            notes = form.notes,
        )
        submit { attendanceRepository.mark(form.row.employee.id, date.value, draft) }
    }

    fun clear() {
        val form = _uiState.value.form ?: return
        submit { attendanceRepository.clear(form.row.employee.id, date.value) }
    }

    private fun submit(action: suspend () -> Unit) {
        _uiState.update { state -> state.copy(form = state.form?.copy(isSaving = true)) }
        viewModelScope.launch {
            runSuspendCatching { action() }
                .onSuccess { closeForm() }
                .onFailure { e ->
                    _uiState.update { state ->
                        state.copy(form = state.form?.copy(isSaving = false, error = e.message ?: "Could not save."))
                    }
                }
        }
    }
}
