package dev.nura.app.field.sync.core.database.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.nura.app.field.sync.domain.model.Employee
import dev.nura.app.field.sync.domain.model.EmployeeStatus

/**
 * Employee master record, owned by the Admin device. Codes stay unique even after soft
 * deletion so a deleted employee's code can never be reused for a different person.
 */
@Entity(
    tableName = "employees",
    indices = [
        Index(value = ["employeeCode"], unique = true),
        Index(value = ["changeSeq"]),
    ],
)
data class EmployeeEntity(
    @PrimaryKey val id: String,
    val employeeCode: String,
    val name: String,
    val mobile: String?,
    val address: String?,
    val dailyWageMinor: Long?,
    val status: EmployeeStatus,
    @Embedded val meta: RecordMeta,
) {
    /** True when the business fields (including deletion) match, ignoring sync metadata. */
    fun sameContentAs(other: EmployeeEntity): Boolean =
        copy(meta = other.meta) == other && meta.isDeleted == other.meta.isDeleted
}

fun EmployeeEntity.toDomain() = Employee(
    id = id,
    employeeCode = employeeCode,
    name = name,
    mobile = mobile,
    address = address,
    dailyWageMinor = dailyWageMinor,
    status = status,
    createdAt = meta.createdAt,
    updatedAt = meta.updatedAt,
    createdBy = meta.createdBy,
    updatedBy = meta.updatedBy,
    version = meta.version,
)
