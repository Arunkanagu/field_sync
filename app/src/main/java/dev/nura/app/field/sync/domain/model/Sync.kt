package dev.nura.app.field.sync.domain.model

/** Record streams that can be transferred, in the order they are sent (parents before children). */
enum class SyncEntityType(val displayName: String) {
    EMPLOYEE("Employees"),
    JOB("Jobs"),
    ATTENDANCE("Attendance"),
}

enum class TrustStatus { TRUSTED, REVOKED }

enum class SyncStatus { IDLE, IN_PROGRESS, SYNCED, FAILED }

enum class TransferStatus(val displayName: String) {
    IN_PROGRESS("In progress"),
    COMPLETED("Completed"),
    FAILED("Failed"),
    CANCELLED("Cancelled"),
}

enum class ConflictStatus { OPEN, RESOLVED, SUPERSEDED }

/**
 * Which device roles may send and receive each record stream (project plan, sections 5 and 10).
 * Employee master data flows only from Admin devices; Managers send attendance and job progress.
 */
object SyncPolicy {

    fun canSend(localRole: Role, type: SyncEntityType): Boolean = when (type) {
        SyncEntityType.EMPLOYEE -> localRole == Role.ADMIN
        SyncEntityType.JOB, SyncEntityType.ATTENDANCE -> true
    }

    fun canReceive(peerRole: Role, type: SyncEntityType): Boolean = when (type) {
        SyncEntityType.EMPLOYEE -> peerRole == Role.ADMIN
        // Job assignment details are applied only from Admin peers; progress from anyone.
        SyncEntityType.JOB, SyncEntityType.ATTENDANCE -> true
    }

    fun sendableTypes(localRole: Role): List<SyncEntityType> =
        SyncEntityType.entries.filter { canSend(localRole, it) }
}

data class TrustedDevice(
    val deviceId: String,
    val displayName: String,
    val role: Role,
    val status: TrustStatus,
    val pairedAt: Long,
    val lastSyncAt: Long?,
)

data class TransferRecord(
    val id: String,
    val peerName: String,
    val peerRole: Role?,
    val startedAt: Long,
    val finishedAt: Long?,
    val status: TransferStatus,
    val sentCount: Int,
    val receivedCount: Int,
    val conflictCount: Int,
    val rejectedCount: Int,
    val errorMessage: String?,
    val initiatedBy: String,
)

data class SyncConflict(
    val id: String,
    val entityType: SyncEntityType,
    val recordId: String,
    val title: String,
    val localSummary: String,
    val remoteSummary: String,
    val remoteDeviceName: String,
    val detectedAt: Long,
)

data class AuditEntry(
    val id: Long,
    val timestamp: Long,
    val actor: String,
    val action: String,
    val entityType: String?,
    val entityId: String?,
    val details: String?,
)
