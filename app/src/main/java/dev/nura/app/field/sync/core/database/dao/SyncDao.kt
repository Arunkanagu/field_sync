package dev.nura.app.field.sync.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import dev.nura.app.field.sync.core.database.entity.ChangeSequenceEntity
import dev.nura.app.field.sync.core.database.entity.ProcessedBatchEntity
import dev.nura.app.field.sync.core.database.entity.SyncConflictEntity
import dev.nura.app.field.sync.core.database.entity.SyncStateEntity
import dev.nura.app.field.sync.core.database.entity.TransferHistoryEntity
import dev.nura.app.field.sync.core.database.entity.TrustedDeviceEntity
import dev.nura.app.field.sync.domain.model.SyncEntityType
import kotlinx.coroutines.flow.Flow

/** Trusted devices, sync cursors, transfer history, idempotency records and conflicts. */
@Dao
abstract class SyncDao {

    // region Change sequence

    @Query("SELECT value FROM change_sequence WHERE id = 0")
    abstract suspend fun currentChangeSeq(): Long?

    @Upsert
    protected abstract suspend fun upsertChangeSeq(sequence: ChangeSequenceEntity)

    /** Allocates the next change sequence. Call inside the transaction that writes the record. */
    @Transaction
    open suspend fun nextChangeSeq(): Long {
        val next = (currentChangeSeq() ?: 0L) + 1
        upsertChangeSeq(ChangeSequenceEntity(value = next))
        return next
    }

    // endregion

    // region Trusted devices

    @Query("SELECT * FROM devices ORDER BY displayName COLLATE NOCASE")
    abstract fun observeDevices(): Flow<List<TrustedDeviceEntity>>

    @Query("SELECT * FROM devices WHERE deviceId = :deviceId")
    abstract suspend fun findDevice(deviceId: String): TrustedDeviceEntity?

    @Upsert
    abstract suspend fun upsertDevice(device: TrustedDeviceEntity)

    @Query("UPDATE devices SET lastSyncAt = :at WHERE deviceId = :deviceId")
    abstract suspend fun markDeviceSynced(deviceId: String, at: Long)

    // endregion

    // region Sync cursors

    @Query("SELECT lastAckedSeq FROM sync_state WHERE peerDeviceId = :peerDeviceId AND entityType = :type")
    abstract suspend fun cursor(peerDeviceId: String, type: SyncEntityType): Long?

    @Query("SELECT * FROM sync_state WHERE peerDeviceId = :peerDeviceId")
    abstract suspend fun syncStates(peerDeviceId: String): List<SyncStateEntity>

    @Upsert
    abstract suspend fun upsertSyncState(state: SyncStateEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertSyncStateIfAbsent(state: SyncStateEntity)

    /**
     * Local changes not yet acknowledged by every trusted peer. With no trusted peer every
     * record counts as pending.
     */
    @Query(
        """
        SELECT
          (SELECT COUNT(*) FROM employees WHERE :includeEmployees AND changeSeq > COALESCE((
             SELECT MIN(s.lastAckedSeq) FROM sync_state s JOIN devices d ON d.deviceId = s.peerDeviceId
             WHERE d.status = 'TRUSTED' AND s.entityType = 'EMPLOYEE'), 0))
        + (SELECT COUNT(*) FROM jobs WHERE changeSeq > COALESCE((
             SELECT MIN(s.lastAckedSeq) FROM sync_state s JOIN devices d ON d.deviceId = s.peerDeviceId
             WHERE d.status = 'TRUSTED' AND s.entityType = 'JOB'), 0))
        + (SELECT COUNT(*) FROM attendance WHERE changeSeq > COALESCE((
             SELECT MIN(s.lastAckedSeq) FROM sync_state s JOIN devices d ON d.deviceId = s.peerDeviceId
             WHERE d.status = 'TRUSTED' AND s.entityType = 'ATTENDANCE'), 0))
        """,
    )
    abstract fun observePendingCount(includeEmployees: Boolean): Flow<Int>

    // endregion

    // region Transfer history

    @Insert
    abstract suspend fun insertHistory(entry: TransferHistoryEntity)

    @Update
    abstract suspend fun updateHistory(entry: TransferHistoryEntity)

    @Query("SELECT * FROM transfer_history ORDER BY startedAt DESC LIMIT :limit")
    abstract fun observeHistory(limit: Int): Flow<List<TransferHistoryEntity>>

    @Query("SELECT MAX(finishedAt) FROM transfer_history WHERE status = 'COMPLETED'")
    abstract fun observeLastSuccessfulSync(): Flow<Long?>

    /** Marks transfers left IN_PROGRESS by a process death as failed. */
    @Query("UPDATE transfer_history SET status = 'FAILED', errorMessage = :message WHERE status = 'IN_PROGRESS'")
    abstract suspend fun failInterruptedTransfers(message: String)

    // endregion

    // region Idempotency

    @Query("SELECT * FROM processed_batches WHERE batchId = :batchId")
    abstract suspend fun findProcessedBatch(batchId: String): ProcessedBatchEntity?

    @Insert
    abstract suspend fun insertProcessedBatch(batch: ProcessedBatchEntity)

    // endregion

    // region Conflicts

    @Query("SELECT * FROM sync_conflicts WHERE status = 'OPEN' ORDER BY detectedAt DESC")
    abstract fun observeOpenConflicts(): Flow<List<SyncConflictEntity>>

    @Query("SELECT COUNT(*) FROM sync_conflicts WHERE status = 'OPEN'")
    abstract fun observeOpenConflictCount(): Flow<Int>

    @Query("SELECT * FROM sync_conflicts WHERE id = :id")
    abstract suspend fun findConflict(id: String): SyncConflictEntity?

    @Insert
    abstract suspend fun insertConflict(conflict: SyncConflictEntity)

    @Update
    abstract suspend fun updateConflict(conflict: SyncConflictEntity)

    /** Closes open conflicts for [recordId] that a newer revision has replaced. */
    @Query(
        "UPDATE sync_conflicts SET status = 'SUPERSEDED', resolvedAt = :at " +
            "WHERE recordId = :recordId AND entityType = :type AND status = 'OPEN'",
    )
    abstract suspend fun supersedeOpenConflicts(recordId: String, type: SyncEntityType, at: Long)

    // endregion
}
