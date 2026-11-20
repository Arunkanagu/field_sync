package dev.nura.app.field.sync.core.sync

import dev.nura.app.field.sync.core.database.entity.AttendanceEntity
import dev.nura.app.field.sync.core.database.entity.AttendanceIds
import dev.nura.app.field.sync.core.database.entity.EmployeeEntity
import dev.nura.app.field.sync.core.database.entity.JobEntity
import dev.nura.app.field.sync.core.database.entity.RecordMeta
import dev.nura.app.field.sync.data.dto.AttendanceDto
import dev.nura.app.field.sync.data.dto.EmployeeDto
import dev.nura.app.field.sync.data.dto.JobDto
import dev.nura.app.field.sync.domain.model.AttendanceStatus
import dev.nura.app.field.sync.domain.model.EmployeeStatus
import dev.nura.app.field.sync.domain.model.JobStatus
import dev.nura.app.field.sync.domain.validation.AttendanceRules
import dev.nura.app.field.sync.domain.validation.EmployeeRules
import dev.nura.app.field.sync.domain.validation.JobRules
import java.time.LocalDate
import java.util.UUID

/** A received record failed validation. [message] is reported back to the sender. */
class InvalidRecordException(message: String) : Exception(message)

/**
 * Entity ↔ DTO mapping. Incoming DTOs are validated with the same rules as local edits;
 * `changeSeq` is device-local, so mapped entities carry 0 until the receiver assigns one.
 */
object RecordMappers {

    private const val MAX_ACTOR = 64
    private val validDates = LocalDate.of(2000, 1, 1).toEpochDay()..LocalDate.of(2200, 1, 1).toEpochDay()

    // region Outgoing

    fun EmployeeEntity.toDto() = EmployeeDto(
        id = id,
        employeeCode = employeeCode,
        name = name,
        mobile = mobile,
        address = address,
        dailyWageMinor = dailyWageMinor,
        status = status.name,
        createdAt = meta.createdAt,
        updatedAt = meta.updatedAt,
        createdBy = meta.createdBy,
        updatedBy = meta.updatedBy,
        deviceId = meta.deviceId,
        version = meta.version,
        baseVersion = meta.baseVersion,
        isDeleted = meta.isDeleted,
    )

    fun AttendanceEntity.toDto() = AttendanceDto(
        id = id,
        employeeId = employeeId,
        workDate = workDate,
        inTime = inTime,
        outTime = outTime,
        status = status.name,
        overtimeMinutes = overtimeMinutes,
        absentReason = absentReason,
        notes = notes,
        createdAt = meta.createdAt,
        updatedAt = meta.updatedAt,
        createdBy = meta.createdBy,
        updatedBy = meta.updatedBy,
        deviceId = meta.deviceId,
        version = meta.version,
        baseVersion = meta.baseVersion,
        isDeleted = meta.isDeleted,
    )

    fun JobEntity.toDto() = JobDto(
        id = id,
        employeeId = employeeId,
        siteId = siteId,
        title = title,
        description = description,
        assignedDate = assignedDate,
        dueDate = dueDate,
        status = status.name,
        progressNote = progressNote,
        progressVersion = progressVersion,
        progressBaseVersion = progressBaseVersion,
        progressUpdatedAt = progressUpdatedAt,
        progressUpdatedBy = progressUpdatedBy,
        createdAt = meta.createdAt,
        updatedAt = meta.updatedAt,
        createdBy = meta.createdBy,
        updatedBy = meta.updatedBy,
        deviceId = meta.deviceId,
        version = meta.version,
        baseVersion = meta.baseVersion,
        isDeleted = meta.isDeleted,
    )

    // endregion

    // region Incoming

    fun EmployeeDto.toEntity(): EmployeeEntity {
        val code = required(employeeCode, "employeeCode")
        val name = required(name, "name")
        check(EmployeeRules.codeError(code) ?: EmployeeRules.nameError(name) ?: EmployeeRules.mobileError(mobile)
            ?: EmployeeRules.addressError(address) ?: EmployeeRules.wageError(dailyWageMinor))
        return EmployeeEntity(
            id = uuid(id),
            employeeCode = code,
            name = name,
            mobile = mobile,
            address = address,
            dailyWageMinor = dailyWageMinor,
            status = enumValue<EmployeeStatus>(status),
            meta = meta(createdAt, updatedAt, createdBy, updatedBy, deviceId, version, baseVersion, isDeleted),
        )
    }

    fun AttendanceDto.toEntity(): AttendanceEntity {
        val id = uuid(id)
        val employeeId = uuid(employeeId)
        val workDate = required(workDate, "workDate").also { if (it !in validDates) invalid("workDate is out of range") }
        if (id != AttendanceIds.forKey(employeeId, workDate)) invalid("id does not match employee and date")
        val status = enumValue<AttendanceStatus>(status)
        val overtime = required(overtimeMinutes, "overtimeMinutes")
        check(AttendanceRules.error(status, inTime, outTime, overtime, absentReason, notes))
        return AttendanceEntity(
            id = id,
            employeeId = employeeId,
            workDate = workDate,
            inTime = inTime,
            outTime = outTime,
            status = status,
            overtimeMinutes = overtime,
            absentReason = absentReason,
            notes = notes,
            meta = meta(createdAt, updatedAt, createdBy, updatedBy, deviceId, version, baseVersion, isDeleted),
        )
    }

    fun JobDto.toEntity(): JobEntity {
        val title = required(title, "title")
        val description = description.orEmpty()
        val assigned = required(assignedDate, "assignedDate").also { if (it !in validDates) invalid("assignedDate is out of range") }
        if (dueDate != null && dueDate !in validDates) invalid("dueDate is out of range")
        check(
            JobRules.error(title, description, LocalDate.ofEpochDay(assigned), dueDate?.let(LocalDate::ofEpochDay))
                ?: JobRules.noteError(progressNote),
        )
        val progressVersion = required(progressVersion, "progressVersion")
        val progressBase = required(progressBaseVersion, "progressBaseVersion")
        if (progressVersion < 1 || progressBase !in 0..progressVersion) invalid("invalid progress version")
        return JobEntity(
            id = uuid(id),
            employeeId = uuid(employeeId),
            siteId = siteId?.let(::uuid),
            title = title,
            description = description,
            assignedDate = assigned,
            dueDate = dueDate,
            status = enumValue<JobStatus>(status),
            progressNote = progressNote,
            progressVersion = progressVersion,
            progressBaseVersion = progressBase,
            progressUpdatedAt = timestamp(progressUpdatedAt, "progressUpdatedAt"),
            progressUpdatedBy = actor(progressUpdatedBy, "progressUpdatedBy"),
            meta = meta(createdAt, updatedAt, createdBy, updatedBy, deviceId, version, baseVersion, isDeleted),
        )
    }

    // endregion

    private fun meta(
        createdAt: Long?,
        updatedAt: Long?,
        createdBy: String?,
        updatedBy: String?,
        deviceId: String?,
        version: Long?,
        baseVersion: Long?,
        isDeleted: Boolean?,
    ): RecordMeta {
        val v = required(version, "version")
        val base = required(baseVersion, "baseVersion")
        if (v < 1 || base !in 0..v) invalid("invalid version")
        return RecordMeta(
            createdAt = timestamp(createdAt, "createdAt"),
            updatedAt = timestamp(updatedAt, "updatedAt"),
            createdBy = actor(createdBy, "createdBy"),
            updatedBy = actor(updatedBy, "updatedBy"),
            deviceId = uuid(deviceId),
            version = v,
            baseVersion = base,
            isDeleted = required(isDeleted, "isDeleted"),
            changeSeq = 0,
        )
    }

    private fun <T : Any> required(value: T?, field: String): T = value ?: invalid("$field is missing")

    private fun timestamp(value: Long?, field: String): Long =
        required(value, field).also { if (it < 0) invalid("$field is invalid") }

    private fun actor(value: String?, field: String): String =
        required(value, field).also { if (it.isBlank() || it.length > MAX_ACTOR) invalid("$field is invalid") }

    private fun uuid(value: String?): String {
        val text = value ?: invalid("id is missing")
        val parsed = runCatching { UUID.fromString(text) }.getOrNull()
        if (parsed == null || parsed.toString() != text.lowercase()) invalid("invalid id")
        return text.lowercase()
    }

    private inline fun <reified E : Enum<E>> enumValue(name: String?): E =
        enumValues<E>().firstOrNull { it.name == name } ?: invalid("unknown ${E::class.simpleName} '$name'")

    private fun check(error: String?) {
        if (error != null) invalid(error)
    }

    private fun invalid(message: String): Nothing = throw InvalidRecordException(message)
}
