package dev.nura.app.field.sync.core.sync

import dev.nura.app.field.sync.core.database.entity.AttendanceIds
import dev.nura.app.field.sync.core.serialization.GsonProvider
import dev.nura.app.field.sync.core.sync.RecordMappers.toDto
import dev.nura.app.field.sync.core.sync.RecordMappers.toEntity
import dev.nura.app.field.sync.data.dto.AttendanceDto
import dev.nura.app.field.sync.data.dto.EmployeeDto
import dev.nura.app.field.sync.data.dto.Envelope
import dev.nura.app.field.sync.data.dto.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

class RecordMappersTest {

    private val employeeId = UUID.randomUUID().toString()
    private val deviceId = UUID.randomUUID().toString()

    private val validEmployee = EmployeeDto(
        id = employeeId,
        employeeCode = "E001",
        name = "Asha",
        mobile = "9876543210",
        status = "ACTIVE",
        createdAt = 1L,
        updatedAt = 2L,
        createdBy = "admin",
        updatedBy = "admin",
        deviceId = deviceId,
        version = 2,
        baseVersion = 1,
        isDeleted = false,
    )

    private fun attendance(workDate: Long = 20_000, id: String = AttendanceIds.forKey(employeeId, workDate)) = AttendanceDto(
        id = id,
        employeeId = employeeId,
        workDate = workDate,
        inTime = 9 * 60,
        outTime = 17 * 60,
        status = "PRESENT",
        overtimeMinutes = 0,
        createdAt = 1L,
        updatedAt = 1L,
        createdBy = "manager",
        updatedBy = "manager",
        deviceId = deviceId,
        version = 1,
        baseVersion = 0,
        isDeleted = false,
    )

    @Test
    fun validEmployee_roundTripsThroughDto() {
        val entity = validEmployee.toEntity()
        assertEquals(0L, entity.meta.changeSeq)
        assertEquals(validEmployee, entity.toDto())
    }

    @Test
    fun invalidEmployees_areRejected() {
        listOf(
            validEmployee.copy(id = "not-a-uuid"),
            validEmployee.copy(name = null),
            validEmployee.copy(employeeCode = "bad code!"),
            validEmployee.copy(status = "FIRED"),
            validEmployee.copy(version = 0),
            validEmployee.copy(baseVersion = 5),
            validEmployee.copy(mobile = "12"),
            validEmployee.copy(isDeleted = null),
        ).forEach { dto ->
            try {
                dto.toEntity()
                fail("Expected rejection for $dto")
            } catch (_: InvalidRecordException) {
            }
        }
    }

    @Test
    fun attendanceId_mustMatchBusinessKey() {
        attendance().toEntity()
        try {
            attendance(id = UUID.randomUUID().toString()).toEntity()
            fail("Attendance with a random ID could create a duplicate for the same employee and day")
        } catch (_: InvalidRecordException) {
        }
    }

    @Test
    fun attendanceOutBeforeIn_isRejected() {
        try {
            attendance().copy(inTime = 600, outTime = 500).toEntity()
            fail()
        } catch (_: InvalidRecordException) {
        }
    }

    @Test
    fun gson_toleratesUnknownFieldsAndMissingValues() {
        val gson = GsonProvider.create()
        val envelope = gson.fromJson(
            """{"protocolVersion":1,"messageType":"HELLO","futureField":42,"hello":{"deviceName":"X"}}""",
            Envelope::class.java,
        )
        assertEquals(MessageType.HELLO, envelope.messageType)
        assertEquals("X", envelope.hello?.deviceName)
        assertNull(envelope.hello?.nonce)

        val unknownType = gson.fromJson("""{"protocolVersion":1,"messageType":"SELF_DESTRUCT"}""", Envelope::class.java)
        assertNull(unknownType.messageType)
    }
}
