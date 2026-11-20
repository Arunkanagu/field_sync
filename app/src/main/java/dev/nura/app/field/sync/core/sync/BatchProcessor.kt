package dev.nura.app.field.sync.core.sync

import com.google.gson.Gson
import dev.nura.app.field.sync.core.database.TransactionRunner
import dev.nura.app.field.sync.core.database.dao.AttendanceDao
import dev.nura.app.field.sync.core.database.dao.EmployeeDao
import dev.nura.app.field.sync.core.database.dao.JobDao
import dev.nura.app.field.sync.core.database.dao.SyncDao
import dev.nura.app.field.sync.core.database.entity.AttendanceEntity
import dev.nura.app.field.sync.core.database.entity.EmployeeEntity
import dev.nura.app.field.sync.core.database.entity.JobEntity
import dev.nura.app.field.sync.core.database.entity.ProcessedBatchEntity
import dev.nura.app.field.sync.core.database.entity.RecordMeta
import dev.nura.app.field.sync.core.database.entity.SyncConflictEntity
import dev.nura.app.field.sync.core.security.PeerIdentity
import dev.nura.app.field.sync.core.sync.RecordMappers.toDto
import dev.nura.app.field.sync.core.sync.RecordMappers.toEntity
import dev.nura.app.field.sync.core.transport.ProtocolViolationException
import dev.nura.app.field.sync.data.dto.BatchAckPayload
import dev.nura.app.field.sync.data.dto.DataBatchPayload
import dev.nura.app.field.sync.data.local.AuditLogger
import dev.nura.app.field.sync.data.local.RecordStamper
import dev.nura.app.field.sync.domain.model.ConflictStatus
import dev.nura.app.field.sync.domain.model.JobStatus
import dev.nura.app.field.sync.domain.model.Role
import dev.nura.app.field.sync.domain.model.SyncEntityType
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Validates and applies one received batch in a single Room transaction, then returns the
 * acknowledgement. The ack is produced only after the commit, and is stored so a re-delivered
 * batch is acknowledged again without being re-applied.
 *
 * Conflict policy (project plan, section 10):
 * - Employees: Admin is authoritative; concurrent edits resolve automatically in its favour.
 * - Attendance: both revisions are kept and the conflict is flagged for review.
 * - Jobs: assignment details are Admin-owned (auto-resolved like employees); concurrent progress
 *   updates are flagged for review.
 */
@Singleton
class BatchProcessor @Inject constructor(
    private val transaction: TransactionRunner,
    private val employeeDao: EmployeeDao,
    private val attendanceDao: AttendanceDao,
    private val jobDao: JobDao,
    private val syncDao: SyncDao,
    private val stamper: RecordStamper,
    private val audit: AuditLogger,
    private val gson: Gson,
) {

    suspend fun process(
        peer: PeerIdentity,
        localRole: Role,
        transferId: String,
        batch: DataBatchPayload,
    ): BatchAckPayload {
        val batchId = batch.batchId?.takeIf { it.length in 1..64 } ?: throw ProtocolViolationException("missing batch ID")
        val type = SyncEntityType.entries.firstOrNull { it.name == batch.entityType }
            ?: throw ProtocolViolationException("unknown entity type")
        val sizes = listOf(batch.employees, batch.attendance, batch.jobs).map { it?.size ?: 0 }
        val declared = when (type) {
            SyncEntityType.EMPLOYEE -> sizes[0]
            SyncEntityType.ATTENDANCE -> sizes[1]
            SyncEntityType.JOB -> sizes[2]
        }
        if (declared == 0 || declared > MAX_BATCH_RECORDS || sizes.sum() != declared) {
            throw ProtocolViolationException("batch contents do not match its type or size limit")
        }

        return transaction.run {
            syncDao.findProcessedBatch(batchId)?.let { done ->
                return@run gson.fromJson(done.ackJson, BatchAckPayload::class.java)
            }
            val context = MergeContext(peer, localRole, stamper.deviceId())
            when (type) {
                SyncEntityType.EMPLOYEE -> batch.employees.orEmpty().forEach { dto ->
                    context.apply(dto.id) { mergeEmployee(dto.toEntity(), context) }
                }
                SyncEntityType.ATTENDANCE -> batch.attendance.orEmpty().forEach { dto ->
                    context.apply(dto.id) { mergeAttendance(dto.toEntity(), context) }
                }
                SyncEntityType.JOB -> batch.jobs.orEmpty().forEach { dto ->
                    context.apply(dto.id) { mergeJob(dto.toEntity(), context) }
                }
            }
            val ack = context.toAck(batchId)
            syncDao.insertProcessedBatch(
                ProcessedBatchEntity(
                    batchId = batchId,
                    transferId = transferId,
                    senderDeviceId = peer.deviceId,
                    entityType = type,
                    recordCount = declared,
                    ackJson = gson.toJson(ack),
                    processedAt = stamper.now(),
                ),
            )
            ack
        }
    }

    // region Employees

    private suspend fun mergeEmployee(remote: EmployeeEntity, ctx: MergeContext): Outcome {
        if (ctx.peer.role != Role.ADMIN) throw InvalidRecordException("employee records are accepted only from Admin devices")
        val local = employeeDao.findById(remote.id)
        return when (decide(local?.meta, remote.meta)) {
            MergeDecision.INSERT -> {
                requireCodeFree(remote)
                employeeDao.insert(remote.copy(meta = accepted(remote.meta, local = null)))
                Outcome.ACCEPTED
            }
            MergeDecision.IGNORE -> Outcome.IGNORED
            MergeDecision.ACCEPT -> {
                local!!
                if (remote.sameContentAs(local)) {
                    employeeDao.update(local.copy(meta = converged(local.meta, remote.meta)))
                } else {
                    requireCodeFree(remote)
                    employeeDao.update(remote.copy(meta = accepted(remote.meta, local.meta)))
                }
                Outcome.ACCEPTED
            }
            MergeDecision.CONFLICT -> {
                local!!
                if (remote.sameContentAs(local)) {
                    employeeDao.update(local.copy(meta = converged(local.meta, remote.meta)))
                    Outcome.IGNORED
                } else {
                    val winner = if (ctx.localRole == Role.ADMIN) local else remote
                    if (winner === remote) requireCodeFree(remote)
                    employeeDao.update(winner.copy(meta = resolved(winner.meta, local.meta, remote.meta, ctx)))
                    audit.log("CONFLICT_AUTO_RESOLVED", "EMPLOYEE", local.id, "Admin revision kept (${ctx.peer.name})", SYNC_ACTOR)
                    Outcome.ACCEPTED
                }
            }
        }
    }

    private suspend fun requireCodeFree(employee: EmployeeEntity) {
        val holder = employeeDao.findByCode(employee.employeeCode)
        if (holder != null && holder.id != employee.id) {
            throw InvalidRecordException("employee code ${employee.employeeCode} is used by a different employee")
        }
    }

    // endregion

    // region Attendance

    private suspend fun mergeAttendance(remote: AttendanceEntity, ctx: MergeContext): Outcome {
        if (employeeDao.findById(remote.employeeId) == null) throw InvalidRecordException("unknown employee")
        val local = attendanceDao.findById(remote.id)
        return when (decide(local?.meta, remote.meta)) {
            MergeDecision.INSERT -> {
                attendanceDao.insert(remote.copy(meta = accepted(remote.meta, local = null)))
                Outcome.ACCEPTED
            }
            MergeDecision.IGNORE -> Outcome.IGNORED
            MergeDecision.ACCEPT -> {
                local!!
                if (remote.sameContentAs(local)) {
                    attendanceDao.update(local.copy(meta = converged(local.meta, remote.meta)))
                } else {
                    attendanceDao.update(remote.copy(meta = accepted(remote.meta, local.meta)))
                    syncDao.supersedeOpenConflicts(remote.id, SyncEntityType.ATTENDANCE, stamper.now())
                }
                Outcome.ACCEPTED
            }
            MergeDecision.CONFLICT -> {
                local!!
                if (remote.sameContentAs(local)) {
                    attendanceDao.update(local.copy(meta = converged(local.meta, remote.meta)))
                    Outcome.IGNORED
                } else {
                    recordConflict(SyncEntityType.ATTENDANCE, local.id, gson.toJson(local.toDto()), gson.toJson(remote.toDto()), ctx)
                    Outcome.CONFLICT
                }
            }
        }
    }

    // endregion

    // region Jobs

    private suspend fun mergeJob(remote: JobEntity, ctx: MergeContext): Outcome {
        if (employeeDao.findById(remote.employeeId) == null) throw InvalidRecordException("unknown employee")
        val peerIsAdmin = ctx.peer.role == Role.ADMIN
        val local = jobDao.findById(remote.id)
        if (local == null) {
            if (!peerIsAdmin) throw InvalidRecordException("jobs can only be created by Admin devices")
            jobDao.insert(
                remote.copy(
                    progressBaseVersion = remote.progressVersion,
                    meta = accepted(remote.meta, local = null),
                ),
            )
            return Outcome.ACCEPTED
        }

        var merged = local
        // Only a locally produced revision (an auto-resolved conflict) has to be sent back.
        var needsResend = false
        var accepted = false
        var conflict = false

        if (peerIsAdmin) {
            when (decide(local.meta, remote.meta)) {
                MergeDecision.ACCEPT -> {
                    merged = merged.withDetailsFrom(remote).copy(
                        meta = remote.meta.copy(baseVersion = remote.meta.version),
                    )
                    accepted = true
                }
                MergeDecision.CONFLICT -> if (remote.sameDetailsAs(local)) {
                    merged = merged.copy(meta = converged(local.meta, remote.meta))
                } else {
                    val winner = if (ctx.localRole == Role.ADMIN) local else remote
                    merged = merged.withDetailsFrom(winner).copy(
                        meta = resolved(winner.meta, local.meta, remote.meta, ctx).copy(changeSeq = local.meta.changeSeq),
                    )
                    needsResend = true
                    accepted = true
                    audit.log("CONFLICT_AUTO_RESOLVED", "JOB", local.id, "Admin assignment details kept (${ctx.peer.name})", SYNC_ACTOR)
                }
                MergeDecision.IGNORE, MergeDecision.INSERT -> Unit
            }
        }

        when (RevisionMerge.decide(local.progressVersion, local.progressBaseVersion, remote.progressVersion, remote.progressBaseVersion)) {
            MergeDecision.ACCEPT -> {
                val touchesCancellation = remote.status != local.status &&
                    (remote.status == JobStatus.CANCELLED || local.status == JobStatus.CANCELLED)
                // Details are only taken from Admin peers, so nothing has been applied yet when this throws.
                if (touchesCancellation && !peerIsAdmin) {
                    throw InvalidRecordException("only Admin devices can cancel or reopen a cancelled job")
                }
                merged = merged.withProgressFrom(remote).copy(
                    progressVersion = remote.progressVersion,
                    progressBaseVersion = remote.progressVersion,
                )
                syncDao.supersedeOpenConflicts(local.id, SyncEntityType.JOB, stamper.now())
                accepted = true
            }
            MergeDecision.CONFLICT -> if (remote.sameProgressAs(local)) {
                val max = maxOf(local.progressVersion, remote.progressVersion)
                merged = merged.copy(progressVersion = max, progressBaseVersion = max)
            } else {
                recordConflict(SyncEntityType.JOB, local.id, gson.toJson(local.toDto()), gson.toJson(remote.toDto()), ctx)
                conflict = true
            }
            MergeDecision.IGNORE, MergeDecision.INSERT -> Unit
        }

        if (merged != local) {
            val seq = if (needsResend) stamper.nextChangeSeq() else local.meta.changeSeq
            jobDao.update(merged.copy(meta = merged.meta.copy(changeSeq = seq)))
        }
        return when {
            conflict -> Outcome.CONFLICT
            accepted -> Outcome.ACCEPTED
            else -> Outcome.IGNORED
        }
    }

    // endregion

    // region Helpers

    private fun decide(local: RecordMeta?, remote: RecordMeta) =
        RevisionMerge.decide(local?.version, local?.baseVersion ?: 0, remote.version, remote.baseVersion)

    /**
     * The remote revision becomes the local one. It keeps the local change sequence (0 for a new
     * record) instead of getting a new one: the sender already has this revision, so queueing it
     * would only echo it back and show as "pending" after a successful sync. Records are therefore
     * not forwarded to third devices; multi-device relaying is a non-goal (project plan, section 2).
     */
    private fun accepted(remote: RecordMeta, local: RecordMeta?) =
        remote.copy(baseVersion = remote.version, changeSeq = local?.changeSeq ?: 0L)

    /** Same content on both sides: just record that both revisions are integrated. No re-send needed. */
    private fun converged(local: RecordMeta, remote: RecordMeta): RecordMeta {
        val max = maxOf(local.version, remote.version)
        return local.copy(version = max, baseVersion = max)
    }

    /** A new revision that includes both sides, carrying [winner]'s content. */
    private suspend fun resolved(winner: RecordMeta, local: RecordMeta, remote: RecordMeta, ctx: MergeContext): RecordMeta {
        val max = maxOf(local.version, remote.version)
        return winner.copy(
            version = max + 1,
            baseVersion = max,
            deviceId = ctx.localDeviceId,
            changeSeq = stamper.nextChangeSeq(),
        )
    }

    private suspend fun recordConflict(
        type: SyncEntityType,
        recordId: String,
        localJson: String,
        remoteJson: String,
        ctx: MergeContext,
    ) {
        val now = stamper.now()
        syncDao.supersedeOpenConflicts(recordId, type, now)
        syncDao.insertConflict(
            SyncConflictEntity(
                id = UUID.randomUUID().toString(),
                entityType = type,
                recordId = recordId,
                localJson = localJson,
                remoteJson = remoteJson,
                remoteDeviceId = ctx.peer.deviceId,
                remoteDeviceName = ctx.peer.name,
                detectedAt = now,
                status = ConflictStatus.OPEN,
                resolution = null,
                resolvedAt = null,
                resolvedBy = null,
            ),
        )
        audit.log("CONFLICT_DETECTED", type.name, recordId, "From ${ctx.peer.name}", SYNC_ACTOR)
    }

    private enum class Outcome { ACCEPTED, IGNORED, CONFLICT }

    private class MergeContext(val peer: PeerIdentity, val localRole: Role, val localDeviceId: String) {
        private var accepted = 0
        private var ignored = 0
        private var conflicts = 0
        private val rejected = mutableListOf<String>()

        suspend fun apply(recordId: String?, merge: suspend () -> Outcome) {
            try {
                when (merge()) {
                    Outcome.ACCEPTED -> accepted++
                    Outcome.IGNORED -> ignored++
                    Outcome.CONFLICT -> conflicts++
                }
            } catch (e: InvalidRecordException) {
                rejected += "${recordId?.take(8) ?: "?"}: ${e.message}"
            }
        }

        fun toAck(batchId: String) = BatchAckPayload(
            batchId = batchId,
            accepted = accepted,
            ignored = ignored,
            conflicts = conflicts,
            rejected = rejected.size,
            rejectedReasons = rejected.take(MAX_REASONS),
        )
    }

    // endregion

    companion object {
        const val MAX_BATCH_RECORDS = 500
        const val SYNC_ACTOR = "sync"
        private const val MAX_REASONS = 20
    }
}
