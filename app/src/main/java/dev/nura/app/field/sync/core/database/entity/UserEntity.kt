package dev.nura.app.field.sync.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.nura.app.field.sync.domain.model.Role
import dev.nura.app.field.sync.domain.model.User

/** Device-local login account. Never included in sync payloads. */
@Entity(
    tableName = "users",
    indices = [Index(value = ["username"], unique = true)],
)
data class UserEntity(
    @PrimaryKey val id: String,
    val username: String,
    val passwordHash: String,
    val role: Role,
    val isActive: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

fun UserEntity.toDomain() = User(
    id = id,
    username = username,
    role = role,
    isActive = isActive,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
