package dev.nura.app.field.sync.data.repository

import dev.nura.app.field.sync.core.database.TransactionRunner
import dev.nura.app.field.sync.core.database.dao.AttendanceDao
import dev.nura.app.field.sync.core.database.dao.EmployeeDao
import dev.nura.app.field.sync.core.database.entity.AttendanceEntity
import dev.nura.app.field.sync.core.database.entity.AttendanceIds
import dev.nura.app.field.sync.core.database.entity.toDomain
import dev.nura.app.field.sync.core.util.today
import dev.nura.app.field.sync.data.local.AuditLogger
import dev.nura.app.field.sync.data.local.RecordStamper
import dev.nura.app.field.sync.domain.auth.PermissionDeniedException
import dev.nura.app.field.sync.domain.auth.SessionManager
import dev.nura.app.field.sync.domain.model.AttendanceDraft
import dev.nura.app.field.sync.domain.model.AttendanceRecord
import dev.nura.app.field.sync.domain.model.AttendanceStatus
import dev.nura.app.field.sync.domain.model.AttendanceSummary
import dev.nura.app.field.sync.domain.model.DailyAttendanceRow
import dev.nura.app.field.sync.domain.model.EmployeeAttendanceReport
import dev.nura.app.field.sync.domain.model.EmployeeStatus
import dev.nura.app.field.sync.domain.model.Permission
import dev.nura.app.field.sync.domain.model.User
import dev.nura.app.field.sync.domain.repository.AttendanceRepository
import dev.nura.app.field.sync.domain.validation.AttendanceRules
import dev.nura.app.field.sync.domain.validation.InputParsing
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AttendanceRepositoryImpl @Inject constructor(
    private val attendanceDao: AttendanceDao,
    private val employeeDao: EmployeeDao,
    private val transaction: TransactionRunner,
    private val stamper: RecordStamper,
    private val audit: AuditLogger,
    private val sessionManager: SessionManager,
    private val clock: Clock,
) : AttendanceRepository {

    override fun today(): LocalDate = clock.today()

    override fun observeDay(date: LocalDate): Flow<List<DailyAttendanceRow>> {
        sessionManager.requirePermission(Permission.VIEW_EMPLOYEES)
        return combine(employeeDao.observeAll(), attendanceDao.observeByDate(date.toEpochDay())) { employees, records ->
            val byEmployee = records.associateBy { it.employeeId }
            employees
                .filter { it.status == EmployeeStatus.ACTIVE || it.id in byEmployee }
                .map { DailyAttendanceRow(it.toDomain(), byEmployee[it.id]?.toDomain()) }
        }
    }

    override fun observeSummary(date: LocalDate): Flow<AttendanceSummary> = observeDay(date).map { rows ->
        val counts = rows.mapNotNull { it.record?.status }.groupingBy { it }.eachCount()
        AttendanceSummary(
            present = counts[AttendanceStatus.PRESENT] ?: 0,
            halfDay = counts[AttendanceStatus.HALF_DAY] ?: 0,
            absent = counts[AttendanceStatus.ABSENT] ?: 0,
            leave = counts[AttendanceStatus.LEAVE] ?: 0,
            unmarked = rows.count { it.record == null },
        )
    }

    override suspend fun mark(employeeId: String, date: LocalDate, draft: AttendanceDraft): AttendanceRecord {
        val actor = sessionManager.requirePermission(Permission.MARK_ATTENDANCE)
        require(!date.isAfter(today())) { "Attendance cannot be marked for a future date." }

        val isAbsent = draft.status == AttendanceStatus.ABSENT
        val inTime = if (isAbsent) null else InputParsing.parseTime(draft.inTime)
        val outTime = if (isAbsent) null else InputParsing.parseTime(draft.outTime)
        val overtime = if (isAbsent) 0 else InputParsing.parseNonNegativeInt(draft.overtimeMinutes, "Overtime")
        val absentReason = if (isAbsent) InputParsing.optionalText(draft.absentReason) else null
        val notes = InputParsing.optionalText(draft.notes)
        val error = AttendanceRules.error(draft.status, inTime, outTime, overtime, absentReason, notes)
        require(error == null) { error.orEmpty() }

        val workDate = date.toEpochDay()
        return transaction.run {
            val employee = employeeDao.findById(employeeId)?.takeUnless { it.meta.isDeleted }
                ?: throw IllegalArgumentException("Employee not found.")
            val id = AttendanceIds.forKey(employeeId, workDate)
            val existing = attendanceDao.findById(id)
            if (existing != null && !existing.meta.isDeleted) requireMayChange(actor, date)

            val candidate = AttendanceEntity(
                id = id,
                employeeId = employeeId,
                workDate = workDate,
                inTime = inTime,
                outTime = outTime,
                status = draft.status,
                overtimeMinutes = overtime,
                absentReason = absentReason,
                notes = notes,
                meta = existing?.meta?.copy(isDeleted = false) ?: stamper.created(actor.username),
            )
            val saved = when {
                existing == null -> candidate.also { attendanceDao.insert(it) }
                candidate.sameContentAs(existing) -> existing
                else -> candidate.copy(meta = stamper.edited(existing.meta, actor.username, isDeleted = false))
                    .also { attendanceDao.update(it) }
            }
            if (saved !== existing) {
                audit.log("ATTENDANCE_MARKED", ENTITY, id, "${employee.employeeCode} · $date · ${draft.status}")
            }
            saved
        }.toDomain()
    }

    override suspend fun clear(employeeId: String, date: LocalDate) {
        val actor = sessionManager.requirePermission(Permission.MARK_ATTENDANCE)
        requireMayChange(actor, date)
        transaction.run {
            val existing = attendanceDao.findById(AttendanceIds.forKey(employeeId, date.toEpochDay()))
                ?.takeUnless { it.meta.isDeleted } ?: return@run
            attendanceDao.update(existing.copy(meta = stamper.edited(existing.meta, actor.username, isDeleted = true)))
            audit.log("ATTENDANCE_CLEARED", ENTITY, existing.id, "$employeeId · $date")
        }
    }

    override fun observeReport(from: LocalDate, to: LocalDate): Flow<List<EmployeeAttendanceReport>> {
        sessionManager.requirePermission(Permission.VIEW_REPORTS)
        return combine(
            employeeDao.observeAll(),
            attendanceDao.observeRange(from.toEpochDay(), to.toEpochDay()),
        ) { employees, records ->
            val byEmployee = records.groupBy { it.employeeId }
            employees
                .filter { it.status == EmployeeStatus.ACTIVE || it.id in byEmployee }
                .map { employee ->
                    val entries = byEmployee[employee.id].orEmpty()
                    val present = entries.count { it.status == AttendanceStatus.PRESENT }
                    val half = entries.count { it.status == AttendanceStatus.HALF_DAY }
                    EmployeeAttendanceReport(
                        employee = employee.toDomain(),
                        presentDays = present,
                        halfDays = half,
                        absentDays = entries.count { it.status == AttendanceStatus.ABSENT },
                        leaveDays = entries.count { it.status == AttendanceStatus.LEAVE },
                        overtimeMinutes = entries.sumOf { it.overtimeMinutes },
                        estimatedWagesMinor = employee.dailyWageMinor?.let { wage -> wage * present + wage * half / 2 },
                    )
                }
        }
    }

    private fun requireMayChange(actor: User, date: LocalDate) {
        if (date != today() && !actor.can(Permission.EDIT_ATTENDANCE)) {
            throw PermissionDeniedException(Permission.EDIT_ATTENDANCE)
        }
    }

    private companion object {
        const val ENTITY = "ATTENDANCE"
    }
}
