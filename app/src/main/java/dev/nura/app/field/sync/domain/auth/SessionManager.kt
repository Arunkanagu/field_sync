package dev.nura.app.field.sync.domain.auth

import dev.nura.app.field.sync.domain.model.Permission
import dev.nura.app.field.sync.domain.model.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

class NotAuthenticatedException : SecurityException("You are not signed in.")

class PermissionDeniedException(val permission: Permission) :
    SecurityException("You do not have permission to ${permission.description}.")

/**
 * Holds the signed-in user for this process. The session is device-local and in memory only,
 * so the app returns to the login screen after process death.
 *
 * Repositories call [requirePermission] so role checks do not depend on the UI hiding buttons.
 */
@Singleton
class SessionManager @Inject constructor() {

    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser: StateFlow<User?> = _currentUser.asStateFlow()

    fun start(user: User) {
        _currentUser.value = user
    }

    fun end() {
        _currentUser.value = null
    }

    fun hasPermission(permission: Permission): Boolean =
        _currentUser.value?.can(permission) == true

    fun requireUser(): User = _currentUser.value ?: throw NotAuthenticatedException()

    fun requirePermission(permission: Permission): User {
        val user = requireUser()
        if (!user.can(permission)) throw PermissionDeniedException(permission)
        return user
    }
}
