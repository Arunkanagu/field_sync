package dev.nura.app.field.sync.domain.repository

import dev.nura.app.field.sync.domain.model.Employee
import dev.nura.app.field.sync.domain.model.EmployeeDraft
import kotlinx.coroutines.flow.Flow

interface EmployeeRepository {

    /** Requires [dev.nura.app.field.sync.domain.model.Permission.VIEW_EMPLOYEES]. Excludes deleted employees. */
    fun observeEmployees(): Flow<List<Employee>>

    /** Requires [dev.nura.app.field.sync.domain.model.Permission.VIEW_EMPLOYEES]. */
    fun observeEmployee(id: String): Flow<Employee?>

    fun observeActiveCount(): Flow<Int>

    /** Requires [dev.nura.app.field.sync.domain.model.Permission.ADD_EMPLOYEES]. */
    suspend fun createEmployee(draft: EmployeeDraft): Employee

    /** Requires [dev.nura.app.field.sync.domain.model.Permission.EDIT_EMPLOYEES]. */
    suspend fun updateEmployee(id: String, draft: EmployeeDraft): Employee

    /** Soft-deletes so the deletion can be synchronized. Requires [dev.nura.app.field.sync.domain.model.Permission.DELETE_EMPLOYEES]. */
    suspend fun deleteEmployee(id: String)
}
