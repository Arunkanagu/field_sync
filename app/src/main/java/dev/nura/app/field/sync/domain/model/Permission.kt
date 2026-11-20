package dev.nura.app.field.sync.domain.model

enum class Permission(val description: String) {
    VIEW_EMPLOYEES("view employees"),
    ADD_EMPLOYEES("add employees"),
    EDIT_EMPLOYEES("edit employee master data"),
    DELETE_EMPLOYEES("delete employees"),
    MARK_ATTENDANCE("mark attendance"),
    EDIT_ATTENDANCE("edit attendance"),
    CREATE_JOBS("create job assignments"),
    UPDATE_JOB_PROGRESS("update job progress"),
    VIEW_REPORTS("view reports"),
    SEND_DATA("send data"),
    RECEIVE_DATA("receive data"),
    MANAGE_USERS("manage local users"),
    VIEW_TRANSFER_HISTORY("view transfer history"),
}

/**
 * Role → permission matrix from the project plan (section 5).
 *
 * Capabilities the plan marks as "restricted" or "optional" for Managers
 * (editing employee master data, editing attendance, creating jobs) are denied
 * until an explicit policy enables them.
 */
object RolePolicy {

    private val managerPermissions = setOf(
        Permission.VIEW_EMPLOYEES,
        Permission.MARK_ATTENDANCE,
        Permission.UPDATE_JOB_PROGRESS,
        Permission.VIEW_REPORTS,
        Permission.SEND_DATA,
        Permission.RECEIVE_DATA,
        Permission.VIEW_TRANSFER_HISTORY,
    )

    fun permissionsFor(role: Role): Set<Permission> = when (role) {
        Role.ADMIN -> Permission.entries.toSet()
        Role.MANAGER -> managerPermissions
    }
}
