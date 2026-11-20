package dev.nura.app.field.sync.domain.repository

import dev.nura.app.field.sync.domain.model.Job
import dev.nura.app.field.sync.domain.model.JobDraft
import dev.nura.app.field.sync.domain.model.JobStatus
import kotlinx.coroutines.flow.Flow

interface JobRepository {

    fun observeJobs(): Flow<List<Job>>

    fun observeJob(id: String): Flow<Job?>

    fun observeActiveCount(): Flow<Int>

    /** Requires [dev.nura.app.field.sync.domain.model.Permission.CREATE_JOBS]. */
    suspend fun createJob(draft: JobDraft): Job

    /** Changes assignment details. Requires [dev.nura.app.field.sync.domain.model.Permission.CREATE_JOBS]. */
    suspend fun updateJob(id: String, draft: JobDraft)

    /** Requires [dev.nura.app.field.sync.domain.model.Permission.CREATE_JOBS]. */
    suspend fun deleteJob(id: String)

    /**
     * Requires [dev.nura.app.field.sync.domain.model.Permission.UPDATE_JOB_PROGRESS]. Only users who can
     * create jobs may cancel a job or change a cancelled one.
     */
    suspend fun updateProgress(id: String, status: JobStatus, note: String)
}
