package dev.nura.app.field.sync.data.repository

import dev.nura.app.field.sync.core.database.TransactionRunner
import dev.nura.app.field.sync.core.database.dao.EmployeeDao
import dev.nura.app.field.sync.core.database.dao.JobDao
import dev.nura.app.field.sync.core.database.entity.JobEntity
import dev.nura.app.field.sync.core.database.entity.toDomain
import dev.nura.app.field.sync.data.local.AuditLogger
import dev.nura.app.field.sync.data.local.RecordStamper
import dev.nura.app.field.sync.domain.auth.PermissionDeniedException
import dev.nura.app.field.sync.domain.auth.SessionManager
import dev.nura.app.field.sync.domain.model.EmployeeStatus
import dev.nura.app.field.sync.domain.model.Job
import dev.nura.app.field.sync.domain.model.JobDraft
import dev.nura.app.field.sync.domain.model.JobStatus
import dev.nura.app.field.sync.domain.model.Permission
import dev.nura.app.field.sync.domain.repository.JobRepository
import dev.nura.app.field.sync.domain.validation.InputParsing
import dev.nura.app.field.sync.domain.validation.JobRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class JobRepositoryImpl @Inject constructor(
    private val jobDao: JobDao,
    private val employeeDao: EmployeeDao,
    private val transaction: TransactionRunner,
    private val stamper: RecordStamper,
    private val audit: AuditLogger,
    private val sessionManager: SessionManager,
) : JobRepository {

    override fun observeJobs(): Flow<List<Job>> {
        requireCanViewJobs()
        return combine(jobDao.observeAll(), employeeDao.observeAll()) { jobs, employees ->
            val names = employees.associate { it.id to it.name }
            jobs.map { it.toDomain(names[it.employeeId]) }
        }
    }

    override fun observeJob(id: String): Flow<Job?> {
        requireCanViewJobs()
        return combine(jobDao.observeById(id), employeeDao.observeAll()) { job, employees ->
            job?.toDomain(employees.firstOrNull { it.id == job.employeeId }?.name)
        }
    }

    override fun observeActiveCount(): Flow<Int> = jobDao.observeActiveCount()

    override suspend fun createJob(draft: JobDraft): Job {
        val actor = sessionManager.requirePermission(Permission.CREATE_JOBS)
        val details = validate(draft)
        return transaction.run {
            val employee = requireAssignableEmployee(details.employeeId)
            val meta = stamper.created(actor.username)
            val job = JobEntity(
                id = UUID.randomUUID().toString(),
                employeeId = details.employeeId,
                siteId = null,
                title = details.title,
                description = details.description,
                assignedDate = details.assignedDate.toEpochDay(),
                dueDate = details.dueDate?.toEpochDay(),
                status = JobStatus.ASSIGNED,
                progressNote = null,
                progressVersion = 1,
                progressBaseVersion = 0,
                progressUpdatedAt = meta.createdAt,
                progressUpdatedBy = actor.username,
                meta = meta,
            )
            jobDao.insert(job)
            audit.log("JOB_CREATED", ENTITY, job.id, "${job.title} → ${employee.employeeCode}")
            job.toDomain(employee.name)
        }
    }

    override suspend fun updateJob(id: String, draft: JobDraft) {
        val actor = sessionManager.requirePermission(Permission.CREATE_JOBS)
        val details = validate(draft)
        transaction.run {
            val existing = requireExisting(id)
            if (details.employeeId != existing.employeeId) requireAssignableEmployee(details.employeeId)
            val candidate = existing.copy(
                employeeId = details.employeeId,
                title = details.title,
                description = details.description,
                assignedDate = details.assignedDate.toEpochDay(),
                dueDate = details.dueDate?.toEpochDay(),
            )
            if (candidate.sameDetailsAs(existing)) return@run
            jobDao.update(candidate.copy(meta = stamper.edited(existing.meta, actor.username)))
            audit.log("JOB_UPDATED", ENTITY, id, candidate.title)
        }
    }

    override suspend fun deleteJob(id: String) {
        val actor = sessionManager.requirePermission(Permission.CREATE_JOBS)
        transaction.run {
            val existing = requireExisting(id)
            jobDao.update(existing.copy(meta = stamper.edited(existing.meta, actor.username, isDeleted = true)))
            audit.log("JOB_DELETED", ENTITY, id, existing.title)
        }
    }

    override suspend fun updateProgress(id: String, status: JobStatus, note: String) {
        val actor = sessionManager.requirePermission(Permission.UPDATE_JOB_PROGRESS)
        val trimmedNote = InputParsing.optionalText(note)
        JobRules.noteError(trimmedNote)?.let { throw IllegalArgumentException(it) }
        transaction.run {
            val existing = requireExisting(id)
            val involvesCancellation = status == JobStatus.CANCELLED || existing.status == JobStatus.CANCELLED
            if (involvesCancellation && status != existing.status && !actor.can(Permission.CREATE_JOBS)) {
                throw PermissionDeniedException(Permission.CREATE_JOBS)
            }
            if (existing.status == status && existing.progressNote == trimmedNote) return@run
            jobDao.update(
                existing.copy(
                    status = status,
                    progressNote = trimmedNote,
                    progressVersion = existing.progressVersion + 1,
                    progressUpdatedAt = stamper.now(),
                    progressUpdatedBy = actor.username,
                    meta = existing.meta.copy(changeSeq = stamper.nextChangeSeq()),
                ),
            )
            audit.log("JOB_PROGRESS", ENTITY, id, "${existing.title}: ${existing.status} → $status")
        }
    }

    private fun requireCanViewJobs() {
        val user = sessionManager.requireUser()
        if (!user.can(Permission.UPDATE_JOB_PROGRESS) && !user.can(Permission.CREATE_JOBS)) {
            throw PermissionDeniedException(Permission.UPDATE_JOB_PROGRESS)
        }
    }

    private suspend fun requireExisting(id: String): JobEntity =
        jobDao.findById(id)?.takeUnless { it.meta.isDeleted } ?: throw IllegalArgumentException("Job not found.")

    private suspend fun requireAssignableEmployee(employeeId: String) =
        employeeDao.findById(employeeId)
            ?.takeIf { !it.meta.isDeleted && it.status == EmployeeStatus.ACTIVE }
            ?: throw IllegalArgumentException("Choose an active employee.")

    private fun validate(draft: JobDraft): ValidDetails {
        val employeeId = requireNotNull(draft.employeeId) { "Choose an employee." }
        val title = draft.title.trim()
        val description = draft.description.trim()
        val assigned = requireNotNull(InputParsing.parseDate(draft.assignedDate, "Assigned date")) {
            "Assigned date is required."
        }
        val due = InputParsing.parseDate(draft.dueDate, "Due date")
        JobRules.error(title, description, assigned, due)?.let { throw IllegalArgumentException(it) }
        return ValidDetails(employeeId, title, description, assigned, due)
    }

    private class ValidDetails(
        val employeeId: String,
        val title: String,
        val description: String,
        val assignedDate: LocalDate,
        val dueDate: LocalDate?,
    )

    private companion object {
        const val ENTITY = "JOB"
    }
}
