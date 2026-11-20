package dev.nura.app.field.sync.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RolePolicyTest {

    private fun user(role: Role, active: Boolean = true) =
        User(id = "id", username = "u", role = role, isActive = active, createdAt = 0, updatedAt = 0)

    @Test
    fun admin_hasEveryPermission() {
        assertEquals(Permission.entries.toSet(), RolePolicy.permissionsFor(Role.ADMIN))
    }

    @Test
    fun manager_cannotManageEmployeeMasterDataOrUsers() {
        val manager = user(Role.MANAGER)
        listOf(
            Permission.ADD_EMPLOYEES,
            Permission.EDIT_EMPLOYEES,
            Permission.DELETE_EMPLOYEES,
            Permission.MANAGE_USERS,
        ).forEach { assertFalse(it.name, manager.can(it)) }
    }

    @Test
    fun manager_canDoFieldWork() {
        val manager = user(Role.MANAGER)
        listOf(
            Permission.VIEW_EMPLOYEES,
            Permission.MARK_ATTENDANCE,
            Permission.UPDATE_JOB_PROGRESS,
            Permission.SEND_DATA,
            Permission.RECEIVE_DATA,
        ).forEach { assertTrue(it.name, manager.can(it)) }
    }

    @Test
    fun inactiveUser_hasNoPermissions() {
        val inactiveAdmin = user(Role.ADMIN, active = false)
        Permission.entries.forEach { assertFalse(it.name, inactiveAdmin.can(it)) }
    }
}
