package dev.nura.app.field.sync.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import dev.nura.app.field.sync.core.database.entity.AuditLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AuditDao {

    @Insert
    suspend fun insert(entry: AuditLogEntity)

    @Query("SELECT * FROM audit_logs ORDER BY timestamp DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AuditLogEntity>>
}
