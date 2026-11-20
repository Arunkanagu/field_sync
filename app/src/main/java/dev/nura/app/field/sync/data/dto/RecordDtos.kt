package dev.nura.app.field.sync.data.dto

/*
 * Transfer DTOs, kept separate from Room entities. Every field is nullable because Gson does not
 * enforce Kotlin nullability; records are validated when they are mapped to entities
 * (see dev.nura.app.field.sync.core.sync.RecordMappers). The device-local changeSeq is never sent.
 */

data class EmployeeDto(
    val id: String? = null,
    val employeeCode: String? = null,
    val name: String? = null,
    val mobile: String? = null,
    val address: String? = null,
    val dailyWageMinor: Long? = null,
    val status: String? = null,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
    val createdBy: String? = null,
    val updatedBy: String? = null,
    val deviceId: String? = null,
    val version: Long? = null,
    val baseVersion: Long? = null,
    val isDeleted: Boolean? = null,
)

data class AttendanceDto(
    val id: String? = null,
    val employeeId: String? = null,
    val workDate: Long? = null,
    val inTime: Int? = null,
    val outTime: Int? = null,
    val status: String? = null,
    val overtimeMinutes: Int? = null,
    val absentReason: String? = null,
    val notes: String? = null,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
    val createdBy: String? = null,
    val updatedBy: String? = null,
    val deviceId: String? = null,
    val version: Long? = null,
    val baseVersion: Long? = null,
    val isDeleted: Boolean? = null,
)

data class JobDto(
    val id: String? = null,
    val employeeId: String? = null,
    val siteId: String? = null,
    val title: String? = null,
    val description: String? = null,
    val assignedDate: Long? = null,
    val dueDate: Long? = null,
    val status: String? = null,
    val progressNote: String? = null,
    val progressVersion: Long? = null,
    val progressBaseVersion: Long? = null,
    val progressUpdatedAt: Long? = null,
    val progressUpdatedBy: String? = null,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
    val createdBy: String? = null,
    val updatedBy: String? = null,
    val deviceId: String? = null,
    val version: Long? = null,
    val baseVersion: Long? = null,
    val isDeleted: Boolean? = null,
)
