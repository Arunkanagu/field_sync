package dev.nura.app.field.sync.domain.model

enum class EmployeeStatus(val displayName: String) {
    ACTIVE("Active"),
    INACTIVE("Inactive"),
}

data class Employee(
    val id: String,
    val employeeCode: String,
    val name: String,
    val mobile: String?,
    val address: String?,
    /** Daily wage in minor currency units (for example paise), or null if not recorded. */
    val dailyWageMinor: Long?,
    val status: EmployeeStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val createdBy: String,
    val updatedBy: String,
    val version: Long,
)

/** Raw form input. The repository validates and normalizes it before saving. */
data class EmployeeDraft(
    val employeeCode: String,
    val name: String,
    val mobile: String = "",
    val address: String = "",
    val dailyWage: String = "",
    val status: EmployeeStatus = EmployeeStatus.ACTIVE,
)
