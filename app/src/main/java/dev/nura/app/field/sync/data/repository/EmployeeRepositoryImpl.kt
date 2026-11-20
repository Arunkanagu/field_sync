package dev.nura.app.field.sync.data.repository

import dev.nura.app.field.sync.core.database.TransactionRunner
import dev.nura.app.field.sync.core.database.dao.EmployeeDao
import dev.nura.app.field.sync.core.database.entity.EmployeeEntity
import dev.nura.app.field.sync.core.database.entity.RecordMeta
import dev.nura.app.field.sync.core.database.entity.toDomain
import dev.nura.app.field.sync.data.local.AuditLogger
import dev.nura.app.field.sync.data.local.RecordStamper
import dev.nura.app.field.sync.domain.auth.SessionManager
import dev.nura.app.field.sync.domain.model.Employee
import dev.nura.app.field.sync.domain.model.EmployeeDraft
import dev.nura.app.field.sync.domain.model.Permission
import dev.nura.app.field.sync.domain.repository.EmployeeRepository
import dev.nura.app.field.sync.domain.validation.EmployeeRules
import dev.nura.app.field.sync.domain.validation.InputParsing
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EmployeeRepositoryImpl @Inject constructor(
    private val employeeDao: EmployeeDao,
    private val transaction: TransactionRunner,
    private val stamper: RecordStamper,
    private val audit: AuditLogger,
    private val sessionManager: SessionManager,
) : EmployeeRepository {

    override fun observeEmployees(): Flow<List<Employee>> {
        sessionManager.requirePermission(Permission.VIEW_EMPLOYEES)
        return employeeDao.observeAll().map { list -> list.map { it.toDomain() } }
    }

    override fun observeEmployee(id: String): Flow<Employee?> {
        sessionManager.requirePermission(Permission.VIEW_EMPLOYEES)
        return employeeDao.observeById(id).map { it?.toDomain() }
    }

    override fun observeActiveCount(): Flow<Int> = employeeDao.observeActiveCount()

    override suspend fun createEmployee(draft: EmployeeDraft): Employee {
        val actor = sessionManager.requirePermission(Permission.ADD_EMPLOYEES)
        val fields = validate(draft)
        return transaction.run {
            requireCodeAvailable(fields.employeeCode, forId = null)
            val entity = fields.toEntity(id = UUID.randomUUID().toString(), meta = stamper.created(actor.username))
            employeeDao.insert(entity)
            audit.log("EMPLOYEE_CREATED", ENTITY, entity.id, "${entity.employeeCode} · ${entity.name}")
            entity
        }.toDomain()
    }

    override suspend fun updateEmployee(id: String, draft: EmployeeDraft): Employee {
        val actor = sessionManager.requirePermission(Permission.EDIT_EMPLOYEES)
        val fields = validate(draft)
        return transaction.run {
            val existing = requireExisting(id)
            requireCodeAvailable(fields.employeeCode, forId = id)
            val candidate = fields.toEntity(id = id, meta = existing.meta)
            if (candidate.sameContentAs(existing)) return@run existing
            val updated = candidate.copy(meta = stamper.edited(existing.meta, actor.username))
            employeeDao.update(updated)
            audit.log("EMPLOYEE_UPDATED", ENTITY, id, "${updated.employeeCode} · ${updated.name} · ${updated.status}")
            updated
        }.toDomain()
    }

    override suspend fun deleteEmployee(id: String) {
        val actor = sessionManager.requirePermission(Permission.DELETE_EMPLOYEES)
        transaction.run {
            val existing = requireExisting(id)
            employeeDao.update(existing.copy(meta = stamper.edited(existing.meta, actor.username, isDeleted = true)))
            audit.log("EMPLOYEE_DELETED", ENTITY, id, "${existing.employeeCode} · ${existing.name}")
        }
    }

    private suspend fun requireExisting(id: String): EmployeeEntity =
        employeeDao.findById(id)?.takeUnless { it.meta.isDeleted } ?: throw IllegalArgumentException("Employee not found.")

    private suspend fun requireCodeAvailable(code: String, forId: String?) {
        val holder = employeeDao.findByCode(code)
        require(holder == null || holder.id == forId) {
            if (holder?.meta?.isDeleted == true) {
                "Employee code $code belonged to a deleted employee and cannot be reused."
            } else {
                "Employee code $code is already in use."
            }
        }
    }

    private fun validate(draft: EmployeeDraft): ValidFields {
        val code = EmployeeRules.normalizeCode(draft.employeeCode)
        val name = draft.name.trim()
        val mobile = EmployeeRules.normalizeMobile(draft.mobile)
        val address = InputParsing.optionalText(draft.address)
        val wage = InputParsing.parseMoney(draft.dailyWage)
        val error = EmployeeRules.codeError(code)
            ?: EmployeeRules.nameError(name)
            ?: EmployeeRules.mobileError(mobile)
            ?: EmployeeRules.addressError(address)
            ?: EmployeeRules.wageError(wage)
        require(error == null) { error.orEmpty() }
        return ValidFields(code, name, mobile, address, wage, draft)
    }

    private class ValidFields(
        val employeeCode: String,
        val name: String,
        val mobile: String?,
        val address: String?,
        val dailyWageMinor: Long?,
        val draft: EmployeeDraft,
    ) {
        fun toEntity(id: String, meta: RecordMeta) = EmployeeEntity(
            id = id,
            employeeCode = employeeCode,
            name = name,
            mobile = mobile,
            address = address,
            dailyWageMinor = dailyWageMinor,
            status = draft.status,
            meta = meta,
        )
    }

    private companion object {
        const val ENTITY = "EMPLOYEE"
    }
}
