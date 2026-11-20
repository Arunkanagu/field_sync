package dev.nura.app.field.sync.core.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class MessageFramerTest {

    @Test
    fun roundTrip_preservesFrameBoundaries() {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        MessageFramer.write(out, "first".toByteArray())
        MessageFramer.write(out, "second message".toByteArray())

        val input = DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
        assertArrayEquals("first".toByteArray(), MessageFramer.read(input))
        assertArrayEquals("second message".toByteArray(), MessageFramer.read(input))
    }

    @Test
    fun oversizedDeclaredLength_isRejectedBeforeAllocating() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).writeInt(Int.MAX_VALUE)
        assertThrows<ProtocolViolationException> {
            MessageFramer.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray())), maxFrameBytes = 1024)
        }
    }

    @Test
    fun negativeOrZeroLength_isRejected() {
        listOf(0, -5).forEach { length ->
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).writeInt(length)
            assertThrows<ProtocolViolationException> { MessageFramer.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray()))) }
        }
    }

    @Test
    fun truncatedPayload_isRejected() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
            writeInt(10)
            write(byteArrayOf(1, 2, 3))
        }
        assertThrows<ProtocolViolationException> { MessageFramer.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray()))) }
    }

    @Test
    fun closedStream_isReportedAsTransferFailure() {
        assertThrows<TransferException> { MessageFramer.read(DataInputStream(ByteArrayInputStream(ByteArray(0)))) }
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
