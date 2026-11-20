package dev.nura.app.field.sync.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import dev.nura.app.field.sync.core.database.entity.UserEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class UserDao {

    @Query("SELECT COUNT(*) FROM users")
    abstract fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM users")
    abstract suspend fun count(): Int

    @Query("SELECT * FROM users ORDER BY username")
    abstract fun observeAll(): Flow<List<UserEntity>>

    @Query("SELECT * FROM users WHERE username = :username LIMIT 1")
    abstract suspend fun findByUsername(username: String): UserEntity?

    @Query("SELECT * FROM users WHERE id = :id LIMIT 1")
    abstract suspend fun findById(id: String): UserEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insert(user: UserEntity)

    @Update
    abstract suspend fun update(user: UserEntity)

    /** Inserts [user] only when the table is empty. Used for first-run device setup. */
    @Transaction
    open suspend fun insertIfEmpty(user: UserEntity): Boolean {
        if (count() > 0) return false
        insert(user)
        return true
    }
}
