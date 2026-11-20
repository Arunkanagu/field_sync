package dev.nura.app.field.sync.domain.repository

import dev.nura.app.field.sync.domain.model.AttendanceDraft
import dev.nura.app.field.sync.domain.model.AttendanceRecord
import dev.nura.app.field.sync.domain.model.AttendanceSummary
import dev.nura.app.field.sync.domain.model.DailyAttendanceRow
import dev.nura.app.field.sync.domain.model.EmployeeAttendanceReport
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * Attendance policy: anyone with MARK_ATTENDANCE can create entries for today or past days and
 * change today's entries. Changing or clearing an entry for an earlier day needs EDIT_ATTENDANCE.
 */
interface AttendanceRepository {

    fun today(): LocalDate

    /** Active employees (plus anyone with an entry that day) and their attendance for [date]. */
    fun observeDay(date: LocalDate): Flow<List<DailyAttendanceRow>>

    fun observeSummary(date: LocalDate): Flow<AttendanceSummary>

    suspend fun mark(employeeId: String, date: LocalDate, draft: AttendanceDraft): AttendanceRecord

    suspend fun clear(employeeId: String, date: LocalDate)

    /** Requires [dev.nura.app.field.sync.domain.model.Permission.VIEW_REPORTS]. */
    fun observeReport(from: LocalDate, to: LocalDate): Flow<List<EmployeeAttendanceReport>>
}
