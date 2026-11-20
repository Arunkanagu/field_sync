package dev.nura.app.field.sync.data.local

import dev.nura.app.field.sync.core.database.dao.SyncDao
import dev.nura.app.field.sync.core.database.entity.RecordMeta
import dev.nura.app.field.sync.core.device.DeviceSettingsRepository
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Produces [RecordMeta] for local writes. Every call allocates a new change sequence, so it
 * must run inside the transaction that persists the record.
 */
@Singleton
class RecordStamper @Inject constructor(
    private val syncDao: SyncDao,
    private val deviceSettings: DeviceSettingsRepository,
    private val clock: Clock,
) {

    @Volatile
    private var cachedDeviceId: String? = null

    suspend fun deviceId(): String =
        cachedDeviceId ?: deviceSettings.getOrCreateDeviceId().also { cachedDeviceId = it }

    fun now(): Long = clock.millis()

    suspend fun nextChangeSeq(): Long = syncDao.nextChangeSeq()

    suspend fun created(actor: String): RecordMeta {
        val now = now()
        return RecordMeta(
            createdAt = now,
            updatedAt = now,
            createdBy = actor,
            updatedBy = actor,
            deviceId = deviceId(),
            version = 1,
            baseVersion = 0,
            isDeleted = false,
            changeSeq = nextChangeSeq(),
        )
    }

    /** A new local revision of [meta]. */
    suspend fun edited(meta: RecordMeta, actor: String, isDeleted: Boolean = meta.isDeleted): RecordMeta =
        meta.copy(
            updatedAt = now(),
            updatedBy = actor,
            deviceId = deviceId(),
            version = meta.version + 1,
            isDeleted = isDeleted,
            changeSeq = nextChangeSeq(),
        )
}
