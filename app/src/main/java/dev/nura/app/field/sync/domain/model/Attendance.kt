package dev.nura.app.field.sync.domain.model

import java.time.LocalDate

enum class AttendanceStatus(val displayName: String, val shortLabel: String) {
    PRESENT("Present", "P"),
    ABSENT("Absent", "A"),
    HALF_DAY("Half day", "H"),
    LEAVE("Leave", "L"),
}

data class AttendanceRecord(
    val id: String,
    val employeeId: String,
    val workDate: LocalDate,
    /** Minutes after midnight. */
    val inTime: Int?,
    val outTime: Int?,
    val status: AttendanceStatus,
    val overtimeMinutes: Int,
    val absentReason: String?,
    val notes: String?,
    val updatedAt: Long,
    val updatedBy: String,
)

/** Raw form input; times are `HH:mm`. */
data class AttendanceDraft(
    val status: AttendanceStatus,
    val inTime: String = "",
    val outTime: String = "",
    val overtimeMinutes: String = "",
    val absentReason: String = "",
    val notes: String = "",
)

data class DailyAttendanceRow(
    val employee: Employee,
    val record: AttendanceRecord?,
)

data class AttendanceSummary(
    val present: Int = 0,
    val halfDay: Int = 0,
    val absent: Int = 0,
    val leave: Int = 0,
    val unmarked: Int = 0,
)

data class EmployeeAttendanceReport(
    val employee: Employee,
    val presentDays: Int,
    val halfDays: Int,
    val absentDays: Int,
    val leaveDays: Int,
    val overtimeMinutes: Int,
    /** Present days × wage + half days × wage / 2, in minor units; null when no wage is recorded. */
    val estimatedWagesMinor: Long?,
)
