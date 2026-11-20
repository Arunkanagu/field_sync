package dev.nura.app.field.sync.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.nura.app.field.sync.core.database.entity.AttendanceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AttendanceDao {

    @Query("SELECT * FROM attendance WHERE workDate = :epochDay AND isDeleted = 0")
    fun observeByDate(epochDay: Long): Flow<List<AttendanceEntity>>

    @Query("SELECT * FROM attendance WHERE workDate BETWEEN :fromEpochDay AND :toEpochDay AND isDeleted = 0")
    fun observeRange(fromEpochDay: Long, toEpochDay: Long): Flow<List<AttendanceEntity>>

    @Query("SELECT * FROM attendance WHERE id = :id")
    suspend fun findById(id: String): AttendanceEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(record: AttendanceEntity)

    @Update
    suspend fun update(record: AttendanceEntity)

    @Query("SELECT * FROM attendance WHERE changeSeq > :afterSeq ORDER BY changeSeq LIMIT :limit")
    suspend fun changedSince(afterSeq: Long, limit: Int): List<AttendanceEntity>
}
