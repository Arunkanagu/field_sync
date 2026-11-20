package dev.nura.app.field.sync.core.database.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.nura.app.field.sync.domain.model.AttendanceRecord
import dev.nura.app.field.sync.domain.model.AttendanceStatus
import java.time.LocalDate
import java.util.UUID

/**
 * One attendance entry per employee per day.
 *
 * The ID is a name-based UUID derived from the business key (see [AttendanceIds]), so two
 * devices that mark the same employee on the same day offline produce the same record ID.
 * That turns a would-be duplicate into an ordinary version conflict that is flagged for review.
 */
@Entity(
    tableName = "attendance",
    indices = [
        Index(value = ["employeeId", "workDate"], unique = true),
        Index(value = ["workDate"]),
        Index(value = ["changeSeq"]),
    ],
)
data class AttendanceEntity(
    @PrimaryKey val id: String,
    val employeeId: String,
    /** [LocalDate.toEpochDay]. */
    val workDate: Long,
    val inTime: Int?,
    val outTime: Int?,
    val status: AttendanceStatus,
    val overtimeMinutes: Int,
    val absentReason: String?,
    val notes: String?,
    @Embedded val meta: RecordMeta,
) {
    fun sameContentAs(other: AttendanceEntity): Boolean =
        copy(meta = other.meta) == other && meta.isDeleted == other.meta.isDeleted
}

object AttendanceIds {
    fun forKey(employeeId: String, workDate: Long): String =
        UUID.nameUUIDFromBytes("fieldsync:attendance:$employeeId:$workDate".toByteArray(Charsets.UTF_8)).toString()
}

fun AttendanceEntity.toDomain() = AttendanceRecord(
    id = id,
    employeeId = employeeId,
    workDate = LocalDate.ofEpochDay(workDate),
    inTime = inTime,
    outTime = outTime,
    status = status,
    overtimeMinutes = overtimeMinutes,
    absentReason = absentReason,
    notes = notes,
    updatedAt = meta.updatedAt,
    updatedBy = meta.updatedBy,
)
