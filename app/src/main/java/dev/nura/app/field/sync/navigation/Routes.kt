package dev.nura.app.field.sync.navigation

object Routes {
    const val DASHBOARD = "dashboard"
    const val USERS = "users"
    const val SETTINGS = "settings"
    const val EMPLOYEES = "employees"
    const val EMPLOYEE_EDIT = "employees/edit?id={id}"
    const val ATTENDANCE = "attendance"
    const val JOBS = "jobs"
    const val JOB_EDIT = "jobs/edit?id={id}"
    const val REPORTS = "reports"
    const val TRANSFER = "transfer"
    const val SYNC_HISTORY = "sync_history"
    const val CONFLICTS = "conflicts"
    const val AUDIT_LOG = "audit_log"

    const val ARG_ID = "id"

    /** Edit an employee, or add one when [id] is null. */
    fun employeeEdit(id: String? = null) = if (id == null) "employees/edit" else "employees/edit?id=$id"

    /** Edit a job's assignment details, or create one when [id] is null. */
    fun jobEdit(id: String? = null) = if (id == null) "jobs/edit" else "jobs/edit?id=$id"
}
