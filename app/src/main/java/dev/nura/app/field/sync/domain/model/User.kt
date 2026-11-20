package dev.nura.app.field.sync.domain.model

/** A local account on this device. Never carries the password hash outside the data layer. */
data class User(
    val id: String,
    val username: String,
    val role: Role,
    val isActive: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun can(permission: Permission): Boolean =
        isActive && permission in RolePolicy.permissionsFor(role)
}

sealed interface LoginResult {
    data class Success(val user: User) : LoginResult
    data object InvalidCredentials : LoginResult
    data object AccountDisabled : LoginResult
    data class LockedOut(val retryAfterSeconds: Long) : LoginResult
}
