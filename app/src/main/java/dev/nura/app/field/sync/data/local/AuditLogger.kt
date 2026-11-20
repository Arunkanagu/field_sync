package dev.nura.app.field.sync.data.local

import dev.nura.app.field.sync.core.database.dao.AuditDao
import dev.nura.app.field.sync.core.database.entity.AuditLogEntity
import dev.nura.app.field.sync.core.database.entity.toDomain
import dev.nura.app.field.sync.domain.auth.SessionManager
import dev.nura.app.field.sync.domain.model.AuditEntry
import dev.nura.app.field.sync.domain.model.Permission
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** Records important user and data actions. Call inside the same transaction as the change. */
@Singleton
class AuditLogger @Inject constructor(
    private val auditDao: AuditDao,
    private val sessionManager: SessionManager,
    private val clock: Clock,
) {

    suspend fun log(
        action: String,
        entityType: String? = null,
        entityId: String? = null,
        details: String? = null,
        actor: String = sessionManager.currentUser.value?.username ?: SYSTEM_ACTOR,
    ) {
        auditDao.insert(
            AuditLogEntity(
                timestamp = clock.millis(),
                actor = actor,
                action = action,
                entityType = entityType,
                entityId = entityId,
                details = details?.take(MAX_DETAILS),
            ),
        )
    }

    /** Requires [Permission.MANAGE_USERS]. */
    fun observeRecent(limit: Int = 300): Flow<List<AuditEntry>> {
        sessionManager.requirePermission(Permission.MANAGE_USERS)
        return auditDao.observeRecent(limit).map { entries -> entries.map { it.toDomain() } }
    }

    companion object {
        const val SYSTEM_ACTOR = "system"
        private const val MAX_DETAILS = 500
    }
}
