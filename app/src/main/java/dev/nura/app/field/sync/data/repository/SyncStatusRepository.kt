package dev.nura.app.field.sync.data.repository

import dev.nura.app.field.sync.core.database.dao.SyncDao
import dev.nura.app.field.sync.core.database.entity.toDomain
import dev.nura.app.field.sync.domain.auth.SessionManager
import dev.nura.app.field.sync.domain.model.Permission
import dev.nura.app.field.sync.domain.model.Role
import dev.nura.app.field.sync.domain.model.TransferRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Read-only sync status for the dashboard and the sync history screen. */
@Singleton
class SyncStatusRepository @Inject constructor(
    private val syncDao: SyncDao,
    private val sessionManager: SessionManager,
) {

    /** Changes not yet acknowledged by every trusted peer. Managers never send employee records. */
    fun observePendingCount(deviceRole: Role): Flow<Int> =
        syncDao.observePendingCount(includeEmployees = deviceRole == Role.ADMIN)

    fun observeLastSuccessfulSync(): Flow<Long?> = syncDao.observeLastSuccessfulSync()

    /** Requires [Permission.VIEW_TRANSFER_HISTORY]. */
    fun observeHistory(limit: Int = 100): Flow<List<TransferRecord>> {
        sessionManager.requirePermission(Permission.VIEW_TRANSFER_HISTORY)
        return syncDao.observeHistory(limit).map { entries -> entries.map { it.toDomain() } }
    }
}
