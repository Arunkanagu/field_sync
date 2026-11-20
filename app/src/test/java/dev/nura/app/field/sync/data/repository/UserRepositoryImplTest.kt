package dev.nura.app.field.sync.data.repository

import dev.nura.app.field.sync.core.database.dao.UserDao
import dev.nura.app.field.sync.core.database.entity.UserEntity
import dev.nura.app.field.sync.core.security.PasswordHasher
import dev.nura.app.field.sync.domain.auth.PermissionDeniedException
import dev.nura.app.field.sync.domain.auth.SessionManager
import dev.nura.app.field.sync.domain.model.LoginResult
import dev.nura.app.field.sync.domain.model.Role
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class UserRepositoryImplTest {

    private val dao = FakeUserDao()
    private val session = SessionManager()
    private val clock = MutableClock()
    private val repository = UserRepositoryImpl(
        userDao = dao,
        passwordHasher = PasswordHasher(iterations = 1_000),
        sessionManager = session,
        clock = clock,
        hashingDispatcher = UnconfinedTestDispatcher(),
    )

    @Test
    fun initialSetup_createsAccountAndSignsIn() = runTest {
        assertFalse(repository.hasAnyUser.first())

        val admin = repository.createInitialAccount("  Admin ", "password1", Role.ADMIN)

        assertEquals("admin", admin.username)
        assertEquals(admin, session.currentUser.value)
        assertTrue(repository.hasAnyUser.first())
        assertFalse(dao.findById(admin.id)!!.passwordHash.contains("password1"))
    }

    @Test
    fun initialSetup_failsWhenDeviceAlreadySetUp() = runTest {
        repository.createInitialAccount("admin", "password1", Role.ADMIN)
        assertThrows<IllegalStateException> {
            repository.createInitialAccount("other", "password1", Role.ADMIN)
        }
    }

    @Test
    fun login_succeedsWithCorrectPasswordAndIgnoresUsernameCase() = runTest {
        repository.createInitialAccount("admin", "password1", Role.ADMIN)
        repository.logout()

        val result = repository.login("ADMIN", "password1")

        assertTrue(result is LoginResult.Success)
        assertEquals(Role.ADMIN, session.currentUser.value?.role)
    }

    @Test
    fun login_rejectsWrongPasswordAndUnknownUser() = runTest {
        repository.createInitialAccount("admin", "password1", Role.ADMIN)
        repository.logout()

        assertEquals(LoginResult.InvalidCredentials, repository.login("admin", "wrong-pass"))
        assertEquals(LoginResult.InvalidCredentials, repository.login("nobody", "password1"))
        assertNull(session.currentUser.value)
    }

    @Test
    fun login_locksOutAfterRepeatedFailures() = runTest {
        repository.createInitialAccount("admin", "password1", Role.ADMIN)
        repository.logout()

        repeat(UserRepositoryImpl.MAX_FAILED_ATTEMPTS) { repository.login("admin", "wrong-pass") }

        assertTrue(repository.login("admin", "password1") is LoginResult.LockedOut)
        clock.advance(UserRepositoryImpl.LOCKOUT_MILLIS)
        assertTrue(repository.login("admin", "password1") is LoginResult.Success)
    }

    @Test
    fun manager_cannotCreateUsers() = runTest {
        repository.createInitialAccount("manager", "password1", Role.MANAGER)

        assertThrows<PermissionDeniedException> {
            repository.createUser("another", "password1", Role.MANAGER)
        }
        assertThrows<PermissionDeniedException> { repository.observeUsers() }
    }

    @Test
    fun admin_canCreateAndDeactivateUsers() = runTest {
        repository.createInitialAccount("admin", "password1", Role.ADMIN)
        val manager = repository.createUser("manager", "password2", Role.MANAGER)

        repository.setUserActive(manager.id, active = false)
        repository.logout()

        assertEquals(LoginResult.AccountDisabled, repository.login("manager", "password2"))
    }

    @Test
    fun admin_cannotDeactivateOwnAccount() = runTest {
        val admin = repository.createInitialAccount("admin", "password1", Role.ADMIN)
        assertThrows<IllegalArgumentException> { repository.setUserActive(admin.id, active = false) }
    }

    @Test
    fun createUser_rejectsDuplicateAndInvalidCredentials() = runTest {
        repository.createInitialAccount("admin", "password1", Role.ADMIN)

        assertThrows<IllegalArgumentException> { repository.createUser("Admin", "password1", Role.MANAGER) }
        assertThrows<IllegalArgumentException> { repository.createUser("ab", "password1", Role.MANAGER) }
        assertThrows<IllegalArgumentException> { repository.createUser("valid.name", "short", Role.MANAGER) }
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return
            throw e
        }
        fail("Expected ${T::class.simpleName}")
    }
}

private class MutableClock(private var millis: Long = 1_800_000_000_000L) : Clock() {
    fun advance(byMillis: Long) {
        millis += byMillis
    }

    override fun millis(): Long = millis
    override fun instant(): Instant = Instant.ofEpochMilli(millis)
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
}

private class FakeUserDao : UserDao() {
    private val users = MutableStateFlow<Map<String, UserEntity>>(emptyMap())

    override fun observeCount(): Flow<Int> = users.map { it.size }
    override suspend fun count(): Int = users.value.size
    override fun observeAll(): Flow<List<UserEntity>> = users.map { it.values.sortedBy(UserEntity::username) }
    override suspend fun findByUsername(username: String) = users.value.values.firstOrNull { it.username == username }
    override suspend fun findById(id: String) = users.value[id]

    override suspend fun insert(user: UserEntity) {
        check(user.id !in users.value && findByUsername(user.username) == null) { "UNIQUE constraint failed" }
        users.update { it + (user.id to user) }
    }

    override suspend fun update(user: UserEntity) {
        users.update { it + (user.id to user) }
    }
}
