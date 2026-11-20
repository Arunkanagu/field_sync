package dev.nura.app.field.sync.core.security

import dev.nura.app.field.sync.core.database.TransactionRunner
import dev.nura.app.field.sync.core.database.dao.SyncDao
import dev.nura.app.field.sync.core.database.entity.SyncStateEntity
import dev.nura.app.field.sync.core.database.entity.TrustedDeviceEntity
import dev.nura.app.field.sync.core.database.entity.toDomain
import dev.nura.app.field.sync.data.local.AuditLogger
import dev.nura.app.field.sync.domain.auth.SessionManager
import dev.nura.app.field.sync.domain.model.Permission
import dev.nura.app.field.sync.domain.model.SyncEntityType
import dev.nura.app.field.sync.domain.model.SyncStatus
import dev.nura.app.field.sync.domain.model.TrustStatus
import dev.nura.app.field.sync.domain.model.TrustedDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Trusted-device registry. Pairing secrets are stored only in Keystore-wrapped form.
 * Pairing (or re-pairing) resets the sync cursors for that peer so everything is offered again;
 * merging is idempotent, so resending already-known records is safe.
 */
@Singleton
class PairingManager @Inject constructor(
    private val syncDao: SyncDao,
    private val keyWrapper: KeyWrapper,
    private val transaction: TransactionRunner,
    private val audit: AuditLogger,
    private val sessionManager: SessionManager,
    private val clock: Clock,
) : PeerTrustStore {

    override suspend fun trustedSecret(deviceId: String): ByteArray? {
        val device = syncDao.findDevice(deviceId)?.takeIf { it.status == TrustStatus.TRUSTED } ?: return null
        // A secret that can no longer be unwrapped (for example after a Keystore reset) means re-pairing.
        return runCatching { keyWrapper.unwrap(device.wrappedSecret) }.getOrNull()
    }

    override suspend fun saveTrustedPeer(peer: PeerIdentity, secret: ByteArray) {
        val now = clock.millis()
        val wrapped = keyWrapper.wrap(secret)
        transaction.run {
            syncDao.upsertDevice(
                TrustedDeviceEntity(
                    deviceId = peer.deviceId,
                    displayName = peer.name,
                    role = peer.role,
                    status = TrustStatus.TRUSTED,
                    wrappedSecret = wrapped,
                    pairedAt = now,
                    lastSyncAt = null,
                ),
            )
            SyncEntityType.entries.forEach { type ->
                syncDao.upsertSyncState(SyncStateEntity(peer.deviceId, type, lastAckedSeq = 0, SyncStatus.IDLE, now))
            }
            audit.log("DEVICE_PAIRED", "DEVICE", peer.deviceId, "${peer.name} (${peer.role.displayName})")
        }
    }

    fun observeDevices(): Flow<List<TrustedDevice>> =
        syncDao.observeDevices().map { devices -> devices.map { it.toDomain() } }

    /** Revokes trust; future sessions from this device are rejected until it is paired again. */
    suspend fun revoke(deviceId: String) {
        sessionManager.requirePermission(Permission.RECEIVE_DATA)
        transaction.run {
            val device = syncDao.findDevice(deviceId) ?: return@run
            syncDao.upsertDevice(device.copy(status = TrustStatus.REVOKED))
            audit.log("DEVICE_REVOKED", "DEVICE", deviceId, device.displayName)
        }
    }
}
