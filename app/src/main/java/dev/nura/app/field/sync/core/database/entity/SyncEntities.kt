package dev.nura.app.field.sync.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.nura.app.field.sync.domain.model.AuditEntry
import dev.nura.app.field.sync.domain.model.ConflictStatus
import dev.nura.app.field.sync.domain.model.Role
import dev.nura.app.field.sync.domain.model.SyncEntityType
import dev.nura.app.field.sync.domain.model.SyncStatus
import dev.nura.app.field.sync.domain.model.TransferRecord
import dev.nura.app.field.sync.domain.model.TransferStatus
import dev.nura.app.field.sync.domain.model.TrustStatus
import dev.nura.app.field.sync.domain.model.TrustedDevice

/**
 * A paired peer. [wrappedSecret] is the pairing secret encrypted with a non-exportable
 * Android Keystore key; the plain secret never touches the database.
 */
@Entity(tableName = "devices")
data class TrustedDeviceEntity(
    @PrimaryKey val deviceId: String,
    val displayName: String,
    val role: Role,
    val status: TrustStatus,
    val wrappedSecret: String,
    val pairedAt: Long,
    val lastSyncAt: Long?,
)

fun TrustedDeviceEntity.toDomain() = TrustedDevice(
    deviceId = deviceId,
    displayName = displayName,
    role = role,
    status = status,
    pairedAt = pairedAt,
    lastSyncAt = lastSyncAt,
)

/** Per-peer, per-stream cursor: every local change with `changeSeq <= lastAckedSeq` was committed by the peer. */
@Entity(tableName = "sync_state", primaryKeys = ["peerDeviceId", "entityType"])
data class SyncStateEntity(
    val peerDeviceId: String,
    val entityType: SyncEntityType,
    val lastAckedSeq: Long,
    val status: SyncStatus,
    val updatedAt: Long,
)

@Entity(tableName = "transfer_history", indices = [Index(value = ["startedAt"])])
data class TransferHistoryEntity(
    @PrimaryKey val id: String,
    val transferId: String?,
    val peerDeviceId: String?,
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

fun TransferHistoryEntity.toDomain() = TransferRecord(
    id = id,
    peerName = peerName,
    peerRole = peerRole,
    startedAt = startedAt,
    finishedAt = finishedAt,
    status = status,
    sentCount = sentCount,
    receivedCount = receivedCount,
    conflictCount = conflictCount,
    rejectedCount = rejectedCount,
    errorMessage = errorMessage,
    initiatedBy = initiatedBy,
)

/** Batches already committed, so a re-delivered batch is acknowledged without being applied twice. */
@Entity(tableName = "processed_batches")
data class ProcessedBatchEntity(
    @PrimaryKey val batchId: String,
    val transferId: String,
    val senderDeviceId: String,
    val entityType: SyncEntityType,
    val recordCount: Int,
    val ackJson: String,
    val processedAt: Long,
)

/** A remote revision that could not be merged automatically. Both revisions are kept as JSON DTOs. */
@Entity(tableName = "sync_conflicts", indices = [Index(value = ["recordId", "status"])])
data class SyncConflictEntity(
    @PrimaryKey val id: String,
    val entityType: SyncEntityType,
    val recordId: String,
    val localJson: String,
    val remoteJson: String,
    val remoteDeviceId: String,
    val remoteDeviceName: String,
    val detectedAt: Long,
    val status: ConflictStatus,
    val resolution: String?,
    val resolvedAt: Long?,
    val resolvedBy: String?,
)

@Entity(tableName = "audit_logs", indices = [Index(value = ["timestamp"])])
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val actor: String,
    val action: String,
    val entityType: String?,
    val entityId: String?,
    val details: String?,
)

fun AuditLogEntity.toDomain() = AuditEntry(
    id = id,
    timestamp = timestamp,
    actor = actor,
    action = action,
    entityType = entityType,
    entityId = entityId,
    details = details,
)

/** Single-row counter backing [RecordMeta.changeSeq]. */
@Entity(tableName = "change_sequence")
data class ChangeSequenceEntity(
    @PrimaryKey val id: Int = 0,
    val value: Long,
)
