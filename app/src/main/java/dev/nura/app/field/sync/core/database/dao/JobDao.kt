package dev.nura.app.field.sync.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.nura.app.field.sync.core.database.entity.JobEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface JobDao {

    @Query("SELECT * FROM jobs WHERE isDeleted = 0 ORDER BY assignedDate DESC, title COLLATE NOCASE")
    fun observeAll(): Flow<List<JobEntity>>

    @Query("SELECT * FROM jobs WHERE id = :id AND isDeleted = 0")
    fun observeById(id: String): Flow<JobEntity?>

    @Query("SELECT COUNT(*) FROM jobs WHERE isDeleted = 0 AND status IN ('ASSIGNED', 'IN_PROGRESS')")
    fun observeActiveCount(): Flow<Int>

    @Query("SELECT * FROM jobs WHERE id = :id")
    suspend fun findById(id: String): JobEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(job: JobEntity)

    @Update
    suspend fun update(job: JobEntity)

    @Query("SELECT * FROM jobs WHERE changeSeq > :afterSeq ORDER BY changeSeq LIMIT :limit")
    suspend fun changedSince(afterSeq: Long, limit: Int): List<JobEntity>
}
