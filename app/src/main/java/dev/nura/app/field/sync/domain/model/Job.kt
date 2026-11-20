package dev.nura.app.field.sync.domain.model

import java.time.LocalDate

enum class JobStatus(val displayName: String) {
    ASSIGNED("Assigned"),
    IN_PROGRESS("In progress"),
    COMPLETED("Completed"),
    CANCELLED("Cancelled"),
    ;

    val isActive: Boolean get() = this == ASSIGNED || this == IN_PROGRESS
}

data class Job(
    val id: String,
    val employeeId: String,
    val employeeName: String?,
    val title: String,
    val description: String,
    val assignedDate: LocalDate,
    val dueDate: LocalDate?,
    val status: JobStatus,
    val progressNote: String?,
    val progressUpdatedAt: Long,
    val progressUpdatedBy: String,
    val createdBy: String,
    val updatedAt: Long,
)

/** Assignment details owned by the Admin. Dates are ISO `yyyy-MM-dd`; due date is optional. */
data class JobDraft(
    val employeeId: String?,
    val title: String,
    val description: String = "",
    val assignedDate: String,
    val dueDate: String = "",
)
