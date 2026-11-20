package dev.nura.app.field.sync.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.nura.app.field.sync.core.database.entity.EmployeeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EmployeeDao {

    @Query("SELECT * FROM employees WHERE isDeleted = 0 ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<EmployeeEntity>>

    @Query("SELECT * FROM employees WHERE id = :id AND isDeleted = 0")
    fun observeById(id: String): Flow<EmployeeEntity?>

    @Query("SELECT COUNT(*) FROM employees WHERE isDeleted = 0 AND status = 'ACTIVE'")
    fun observeActiveCount(): Flow<Int>

    @Query("SELECT * FROM employees WHERE id = :id")
    suspend fun findById(id: String): EmployeeEntity?

    @Query("SELECT * FROM employees WHERE employeeCode = :code LIMIT 1")
    suspend fun findByCode(code: String): EmployeeEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(employee: EmployeeEntity)

    @Update
    suspend fun update(employee: EmployeeEntity)

    @Query("SELECT * FROM employees WHERE changeSeq > :afterSeq ORDER BY changeSeq LIMIT :limit")
    suspend fun changedSince(afterSeq: Long, limit: Int): List<EmployeeEntity>
}
