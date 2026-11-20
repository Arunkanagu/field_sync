package dev.nura.app.field.sync.core.sync

import com.google.gson.Gson
import dev.nura.app.field.sync.core.database.TransactionRunner
import dev.nura.app.field.sync.core.database.dao.AttendanceDao
import dev.nura.app.field.sync.core.database.dao.EmployeeDao
import dev.nura.app.field.sync.core.database.dao.JobDao
import dev.nura.app.field.sync.core.database.dao.SyncDao
import dev.nura.app.field.sync.core.database.entity.SyncConflictEntity
import dev.nura.app.field.sync.core.sync.RecordMappers.toEntity
import dev.nura.app.field.sync.core.util.formatForDisplay
import dev.nura.app.field.sync.data.dto.AttendanceDto
import dev.nura.app.field.sync.data.dto.JobDto
import dev.nura.app.field.sync.data.local.AuditLogger
import dev.nura.app.field.sync.data.local.RecordStamper
import dev.nura.app.field.sync.domain.auth.PermissionDeniedException
import dev.nura.app.field.sync.domain.auth.SessionManager
import dev.nura.app.field.sync.domain.model.AttendanceStatus
import dev.nura.app.field.sync.domain.model.ConflictStatus
import dev.nura.app.field.sync.domain.model.JobStatus
import dev.nura.app.field.sync.domain.model.Permission
import dev.nura.app.field.sync.domain.model.SyncConflict
import dev.nura.app.field.sync.domain.model.SyncEntityType
import dev.nura.app.field.sync.domain.validation.InputParsing
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Review screen backend for conflicts that were not resolved automatically. Resolving writes a
 * new revision that includes both sides (version = max + 1, base = max), so the other device
 * accepts it as a fast-forward on the next transfer and closes its own copy of the conflict.
 */
@Singleton
class ConflictResolver @Inject constructor(
    private val transaction: TransactionRunner,
    private val syncDao: SyncDao,
    private val employeeDao: EmployeeDao,
    private val attendanceDao: AttendanceDao,
    private val jobDao: JobDao,
    private val stamper: RecordStamper,
    private val audit: AuditLogger,
    private val sessionManager: SessionManager,
    private val gson: Gson,
) {

    fun observeOpenConflicts(): Flow<List<SyncConflict>> =
        combine(syncDao.observeOpenConflicts(), employeeDao.observeAll()) { conflicts, employees ->
            val names = employees.associate { it.id to it.name }
            conflicts.mapNotNull { runCatching { it.toModel(names) }.getOrNull() }
        }

    fun observeOpenCount(): Flow<Int> = syncDao.observeOpenConflictCount()

    suspend fun resolve(conflictId: String, keepLocal: Boolean) {
        val actor = sessionManager.requireUser()
        transaction.run {
            val conflict = syncDao.findConflict(conflictId)?.takeIf { it.status == ConflictStatus.OPEN }
                ?: throw IllegalArgumentException("This conflict has already been resolved.")
            when (conflict.entityType) {
                SyncEntityType.ATTENDANCE -> {
                    if (!actor.can(Permission.MARK_ATTENDANCE)) throw PermissionDeniedException(Permission.MARK_ATTENDANCE)
                    val local = attendanceDao.findById(conflict.recordId) ?: throw IllegalArgumentException("Record not found.")
                    val remote = gson.fromJson(conflict.remoteJson, AttendanceDto::class.java).toEntity()
                    val content = if (keepLocal) local else remote
                    val max = maxOf(local.meta.version, remote.meta.version)
                    attendanceDao.update(
                        content.copy(
                            meta = local.meta.copy(
                                version = max + 1,
                                baseVersion = max,
                                isDeleted = content.meta.isDeleted,
                                updatedAt = stamper.now(),
                                updatedBy = actor.username,
                                deviceId = stamper.deviceId(),
                                changeSeq = stamper.nextChangeSeq(),
                            ),
                        ),
                    )
                }
                SyncEntityType.JOB -> {
                    if (!actor.can(Permission.UPDATE_JOB_PROGRESS)) throw PermissionDeniedException(Permission.UPDATE_JOB_PROGRESS)
                    val local = jobDao.findById(conflict.recordId) ?: throw IllegalArgumentException("Job not found.")
                    val remote = gson.fromJson(conflict.remoteJson, JobDto::class.java).toEntity()
                    val content = if (keepLocal) local else local.withProgressFrom(remote)
                    val max = maxOf(local.progressVersion, remote.progressVersion)
                    jobDao.update(
                        content.copy(
                            progressVersion = max + 1,
                            progressBaseVersion = max,
                            progressUpdatedAt = stamper.now(),
                            progressUpdatedBy = actor.username,
                            meta = local.meta.copy(changeSeq = stamper.nextChangeSeq()),
                        ),
                    )
                }
                SyncEntityType.EMPLOYEE -> throw IllegalStateException("Employee conflicts are resolved automatically.")
            }
            syncDao.updateConflict(
                conflict.copy(
                    status = ConflictStatus.RESOLVED,
                    resolution = if (keepLocal) "KEPT_LOCAL" else "USED_REMOTE",
                    resolvedAt = stamper.now(),
                    resolvedBy = actor.username,
                ),
            )
            audit.log(
                "CONFLICT_RESOLVED",
                conflict.entityType.name,
                conflict.recordId,
                if (keepLocal) "Kept this device's revision" else "Used ${conflict.remoteDeviceName}'s revision",
            )
        }
    }

    private fun SyncConflictEntity.toModel(employeeNames: Map<String, String>): SyncConflict = when (entityType) {
        SyncEntityType.ATTENDANCE -> {
            val local = gson.fromJson(localJson, AttendanceDto::class.java)
            val remote = gson.fromJson(remoteJson, AttendanceDto::class.java)
            val date = local.workDate?.let { LocalDate.ofEpochDay(it).formatForDisplay() }.orEmpty()
            SyncConflict(
                id = id,
                entityType = entityType,
                recordId = recordId,
                title = "Attendance · ${employeeNames[local.employeeId] ?: "Unknown employee"} · $date",
                localSummary = local.summary(),
                remoteSummary = remote.summary(),
                remoteDeviceName = remoteDeviceName,
                detectedAt = detectedAt,
            )
        }
        SyncEntityType.JOB -> {
            val local = gson.fromJson(localJson, JobDto::class.java)
            val remote = gson.fromJson(remoteJson, JobDto::class.java)
            SyncConflict(
                id = id,
                entityType = entityType,
                recordId = recordId,
                title = "Job progress · ${local.title.orEmpty()}",
                localSummary = local.progressSummary(),
                remoteSummary = remote.progressSummary(),
                remoteDeviceName = remoteDeviceName,
                detectedAt = detectedAt,
            )
        }
        SyncEntityType.EMPLOYEE -> error("Employee conflicts are not stored")
    }

    private fun AttendanceDto.summary(): String {
        if (isDeleted == true) return "Entry cleared · by $updatedBy"
        val statusName = AttendanceStatus.entries.firstOrNull { it.name == status }?.displayName ?: status
        val times = if (inTime != null || outTime != null) {
            " · ${InputParsing.formatTime(inTime).ifEmpty { "?" }}–${InputParsing.formatTime(outTime).ifEmpty { "?" }}"
        } else {
            ""
        }
        val overtime = overtimeMinutes?.takeIf { it > 0 }?.let { " · OT $it min" }.orEmpty()
        val reason = absentReason?.let { " · $it" }.orEmpty()
        val note = notes?.let { " · \"$it\"" }.orEmpty()
        return "$statusName$times$overtime$reason$note · by $updatedBy"
    }

    private fun JobDto.progressSummary(): String {
        val statusName = JobStatus.entries.firstOrNull { it.name == status }?.displayName ?: status
        val note = progressNote?.let { " · \"$it\"" }.orEmpty()
        return "$statusName$note · by $progressUpdatedBy"
    }
}
