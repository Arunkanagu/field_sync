package dev.nura.app.field.sync.data.repository

import dev.nura.app.field.sync.core.database.dao.UserDao
import dev.nura.app.field.sync.core.database.entity.UserEntity
import dev.nura.app.field.sync.core.database.entity.toDomain
import dev.nura.app.field.sync.core.di.DefaultDispatcher
import dev.nura.app.field.sync.core.security.PasswordHasher
import dev.nura.app.field.sync.domain.auth.CredentialRules
import dev.nura.app.field.sync.domain.auth.SessionManager
import dev.nura.app.field.sync.domain.model.LoginResult
import dev.nura.app.field.sync.domain.model.Permission
import dev.nura.app.field.sync.domain.model.Role
import dev.nura.app.field.sync.domain.model.User
import dev.nura.app.field.sync.domain.repository.UserRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserRepositoryImpl @Inject constructor(
    private val userDao: UserDao,
    private val passwordHasher: PasswordHasher,
    private val sessionManager: SessionManager,
    private val clock: Clock,
    @param:DefaultDispatcher private val hashingDispatcher: CoroutineDispatcher,
) : UserRepository {

    private val loginMutex = Mutex()
    private var failedAttempts = 0
    private var lockedUntilMillis = 0L

    /** Verified against unknown usernames so a miss costs as much time as a wrong password. */
    private val timingDummyHash by lazy { passwordHasher.hash(UUID.randomUUID().toString()) }

    override val hasAnyUser: Flow<Boolean> =
        userDao.observeCount().map { it > 0 }.distinctUntilChanged()

    override suspend fun createInitialAccount(username: String, password: String, role: Role): User {
        val entity = newUserEntity(username, password, role)
        check(userDao.insertIfEmpty(entity)) { "This device has already been set up." }
        return entity.toDomain().also(sessionManager::start)
    }

    override suspend fun login(username: String, password: String): LoginResult = loginMutex.withLock {
        val now = clock.millis()
        if (now < lockedUntilMillis) {
            return LoginResult.LockedOut(retryAfterSeconds = (lockedUntilMillis - now + 999) / 1000)
        }

        val user = userDao.findByUsername(CredentialRules.normalizeUsername(username))
        val passwordMatches = withContext(hashingDispatcher) {
            passwordHasher.verify(password, user?.passwordHash ?: timingDummyHash)
        }
        if (user == null || !passwordMatches) {
            if (++failedAttempts >= MAX_FAILED_ATTEMPTS) {
                failedAttempts = 0
                lockedUntilMillis = now + LOCKOUT_MILLIS
            }
            return LoginResult.InvalidCredentials
        }
        failedAttempts = 0
        if (!user.isActive) return LoginResult.AccountDisabled

        if (passwordHasher.needsRehash(user.passwordHash)) {
            userDao.update(user.copy(passwordHash = hash(password), updatedAt = clock.millis()))
        }
        LoginResult.Success(user.toDomain().also(sessionManager::start))
    }

    override fun logout() = sessionManager.end()

    override fun observeUsers(): Flow<List<User>> {
        sessionManager.requirePermission(Permission.MANAGE_USERS)
        return userDao.observeAll().map { users -> users.map { it.toDomain() } }
    }

    override suspend fun createUser(username: String, password: String, role: Role): User {
        sessionManager.requirePermission(Permission.MANAGE_USERS)
        val entity = newUserEntity(username, password, role)
        require(userDao.findByUsername(entity.username) == null) { "Username is already taken." }
        userDao.insert(entity)
        return entity.toDomain()
    }

    override suspend fun setUserActive(userId: String, active: Boolean) {
        val actor = sessionManager.requirePermission(Permission.MANAGE_USERS)
        require(actor.id != userId) { "You cannot change the status of your own account." }
        val user = requireNotNull(userDao.findById(userId)) { "User not found." }
        if (user.isActive != active) {
            userDao.update(user.copy(isActive = active, updatedAt = clock.millis()))
        }
    }

    private suspend fun newUserEntity(username: String, password: String, role: Role): UserEntity {
        val normalized = CredentialRules.normalizeUsername(username)
        CredentialRules.requireValid(normalized, password)
        val now = clock.millis()
        return UserEntity(
            id = UUID.randomUUID().toString(),
            username = normalized,
            passwordHash = hash(password),
            role = role,
            isActive = true,
            createdAt = now,
            updatedAt = now,
        )
    }

    private suspend fun hash(password: String): String =
        withContext(hashingDispatcher) { passwordHasher.hash(password) }

    companion object {
        const val MAX_FAILED_ATTEMPTS = 5
        const val LOCKOUT_MILLIS = 30_000L
    }
}
