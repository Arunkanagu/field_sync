package dev.nura.app.field.sync.core.sync

import dev.nura.app.field.sync.core.database.dao.AttendanceDao
import dev.nura.app.field.sync.core.database.dao.EmployeeDao
import dev.nura.app.field.sync.core.database.dao.JobDao
import dev.nura.app.field.sync.core.database.dao.SyncDao
import dev.nura.app.field.sync.core.database.entity.SyncStateEntity
import dev.nura.app.field.sync.core.sync.RecordMappers.toDto
import dev.nura.app.field.sync.data.dto.DataBatchPayload
import dev.nura.app.field.sync.domain.model.SyncEntityType
import dev.nura.app.field.sync.domain.model.SyncStatus
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

class OutgoingBatch(
    val payload: DataBatchPayload,
    val recordCount: Int,
    /** Highest local change sequence in the batch; the cursor moves here once the peer acknowledges. */
    val lastSeq: Long,
)

/**
 * Builds incremental batches from the per-peer cursor in `sync_state`. Only acknowledged
 * batches advance the cursor, so an interrupted transfer resumes from the last committed batch.
 */
@Singleton
class BatchBuilder @Inject constructor(
    private val employeeDao: EmployeeDao,
    private val attendanceDao: AttendanceDao,
    private val jobDao: JobDao,
    private val syncDao: SyncDao,
    private val clock: Clock,
) {

    suspend fun nextBatch(localDeviceId: String, peerDeviceId: String, type: SyncEntityType): OutgoingBatch? {
        val cursor = syncDao.cursor(peerDeviceId, type) ?: 0L
        val (payload, seqs) = when (type) {
            SyncEntityType.EMPLOYEE -> employeeDao.changedSince(cursor, BATCH_SIZE).let { records ->
                DataBatchPayload(employees = records.map { it.toDto() }) to records.map { it.meta.changeSeq }
            }
            SyncEntityType.ATTENDANCE -> attendanceDao.changedSince(cursor, BATCH_SIZE).let { records ->
                DataBatchPayload(attendance = records.map { it.toDto() }) to records.map { it.meta.changeSeq }
            }
            SyncEntityType.JOB -> jobDao.changedSince(cursor, BATCH_SIZE).let { records ->
                DataBatchPayload(jobs = records.map { it.toDto() }) to records.map { it.meta.changeSeq }
            }
        }
        if (seqs.isEmpty()) return null
        return OutgoingBatch(
            payload = payload.copy(batchId = batchId(localDeviceId, type, seqs), entityType = type.name),
            recordCount = seqs.size,
            lastSeq = seqs.max(),
        )
    }

    suspend fun markStarted(peerDeviceId: String, type: SyncEntityType) = setStatus(peerDeviceId, type, SyncStatus.IN_PROGRESS)

    suspend fun markFinished(peerDeviceId: String, type: SyncEntityType, success: Boolean) =
        setStatus(peerDeviceId, type, if (success) SyncStatus.SYNCED else SyncStatus.FAILED)

    /** Called only after the peer acknowledged that the batch was committed. */
    suspend fun acknowledge(peerDeviceId: String, type: SyncEntityType, lastSeq: Long) {
        val current = syncDao.cursor(peerDeviceId, type) ?: 0L
        syncDao.upsertSyncState(
            SyncStateEntity(peerDeviceId, type, maxOf(current, lastSeq), SyncStatus.IN_PROGRESS, clock.millis()),
        )
    }

    private suspend fun setStatus(peerDeviceId: String, type: SyncEntityType, status: SyncStatus) {
        val cursor = syncDao.cursor(peerDeviceId, type) ?: 0L
        syncDao.upsertSyncState(SyncStateEntity(peerDeviceId, type, cursor, status, clock.millis()))
    }

    /**
     * Deterministic: re-sending the same unchanged records after a disconnect yields the same ID,
     * so the receiver can answer from `processed_batches` without applying them twice.
     */
    private fun batchId(localDeviceId: String, type: SyncEntityType, seqs: List<Long>): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$localDeviceId|${type.name}|${seqs.joinToString(",")}".toByteArray(Charsets.UTF_8))
        return UUID.nameUUIDFromBytes(digest).toString()
    }

    companion object {
        const val BATCH_SIZE = 100
    }
}
