package dev.nura.app.field.sync.domain.repository

import dev.nura.app.field.sync.domain.model.LoginResult
import dev.nura.app.field.sync.domain.model.Role
import dev.nura.app.field.sync.domain.model.User
import kotlinx.coroutines.flow.Flow

interface UserRepository {

    /** Emits false until the first account has been created on this device. */
    val hasAnyUser: Flow<Boolean>

    /** Creates the first account on a fresh device and signs it in. Fails if any account exists. */
    suspend fun createInitialAccount(username: String, password: String, role: Role): User

    suspend fun login(username: String, password: String): LoginResult

    fun logout()

    /** Requires [dev.nura.app.field.sync.domain.model.Permission.MANAGE_USERS]. */
    fun observeUsers(): Flow<List<User>>

    /** Requires [dev.nura.app.field.sync.domain.model.Permission.MANAGE_USERS]. */
    suspend fun createUser(username: String, password: String, role: Role): User

    /** Requires [dev.nura.app.field.sync.domain.model.Permission.MANAGE_USERS]. */
    suspend fun setUserActive(userId: String, active: Boolean)
}
