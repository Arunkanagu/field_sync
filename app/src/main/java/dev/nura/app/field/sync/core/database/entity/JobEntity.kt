package dev.nura.app.field.sync.core.database.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.nura.app.field.sync.domain.model.Job
import dev.nura.app.field.sync.domain.model.JobStatus
import java.time.LocalDate

/**
 * A job assigned to one employee.
 *
 * Fields are versioned on two independent tracks so Admin and Manager can both work offline:
 * - assignment details (title, dates, employee, deletion) use [meta] and are owned by the Admin;
 * - progress ([status], [progressNote]) uses the `progress*` columns and may be updated by Managers.
 */
@Entity(
    tableName = "jobs",
    indices = [
        Index(value = ["employeeId"]),
        Index(value = ["changeSeq"]),
    ],
)
data class JobEntity(
    @PrimaryKey val id: String,
    val employeeId: String,
    val siteId: String?,
    val title: String,
    val description: String,
    /** [LocalDate.toEpochDay]. */
    val assignedDate: Long,
    val dueDate: Long?,
    val status: JobStatus,
    val progressNote: String?,
    val progressVersion: Long,
    val progressBaseVersion: Long,
    val progressUpdatedAt: Long,
    val progressUpdatedBy: String,
    @Embedded val meta: RecordMeta,
) {
    fun sameDetailsAs(other: JobEntity): Boolean =
        employeeId == other.employeeId && siteId == other.siteId && title == other.title &&
            description == other.description && assignedDate == other.assignedDate &&
            dueDate == other.dueDate && meta.isDeleted == other.meta.isDeleted

    fun sameProgressAs(other: JobEntity): Boolean =
        status == other.status && progressNote == other.progressNote

    /** Copies the Admin-owned assignment details (not their versions) from [source]. */
    fun withDetailsFrom(source: JobEntity) = copy(
        employeeId = source.employeeId,
        siteId = source.siteId,
        title = source.title,
        description = source.description,
        assignedDate = source.assignedDate,
        dueDate = source.dueDate,
    )

    fun withProgressFrom(source: JobEntity) = copy(
        status = source.status,
        progressNote = source.progressNote,
        progressUpdatedAt = source.progressUpdatedAt,
        progressUpdatedBy = source.progressUpdatedBy,
    )
}

fun JobEntity.toDomain(employeeName: String?) = Job(
    id = id,
    employeeId = employeeId,
    employeeName = employeeName,
    title = title,
    description = description,
    assignedDate = LocalDate.ofEpochDay(assignedDate),
    dueDate = dueDate?.let(LocalDate::ofEpochDay),
    status = status,
    progressNote = progressNote,
    progressUpdatedAt = progressUpdatedAt,
    progressUpdatedBy = progressUpdatedBy,
    createdBy = meta.createdBy,
    updatedAt = maxOf(meta.updatedAt, progressUpdatedAt),
)
