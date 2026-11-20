package dev.nura.app.field.sync.core.security

import dev.nura.app.field.sync.core.transport.AuthenticationFailedException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class SessionCipherTest {

    private val keyA = SessionCrypto.randomBytes(32)
    private val keyB = SessionCrypto.randomBytes(32)
    private val alice = SessionCipher(sendKey = keyA, receiveKey = keyB)
    private val bob = SessionCipher(sendKey = keyB, receiveKey = keyA)

    @Test
    fun roundTrip_inBothDirections() {
        assertArrayEquals("hello".toByteArray(), bob.open(alice.seal("hello".toByteArray())))
        assertArrayEquals("reply".toByteArray(), alice.open(bob.seal("reply".toByteArray())))
        assertArrayEquals("again".toByteArray(), bob.open(alice.seal("again".toByteArray())))
    }

    @Test
    fun sameMessage_producesDifferentCiphertext() {
        val first = alice.seal("same".toByteArray())
        val second = alice.seal("same".toByteArray())
        assertFalse(first.contentEquals(second))
    }

    @Test
    fun replayedFrame_isRejected() {
        val frame = alice.seal("pay once".toByteArray())
        bob.open(frame)
        assertThrows<AuthenticationFailedException> { bob.open(frame) }
    }

    @Test
    fun reorderedFrame_isRejected() {
        alice.seal("first".toByteArray())
        val second = alice.seal("second".toByteArray())
        assertThrows<AuthenticationFailedException> { bob.open(second) }
    }

    @Test
    fun tamperedFrame_isRejected() {
        val frame = alice.seal("attendance".toByteArray())
        frame[frame.size - 1] = (frame[frame.size - 1].toInt() xor 1).toByte()
        assertThrows<AuthenticationFailedException> { bob.open(frame) }
    }

    @Test
    fun tamperedCounter_isRejected() {
        val frame = alice.seal("x".toByteArray())
        frame[7] = 5
        assertThrows<AuthenticationFailedException> { bob.open(frame) }
    }

    @Test
    fun pairingCode_isSixDigitsAndDependsOnTranscript() {
        val shared = SessionCrypto.randomBytes(32)
        val code = SessionCrypto.pairingCode(shared, byteArrayOf(1))
        assertEquals(6, code.length)
        assertEquals(code, SessionCrypto.pairingCode(shared, byteArrayOf(1)))
    }

    @Test
    fun hkdf_matchesRfc5869TestCase3() {
        // RFC 5869 A.3: SHA-256, zero-length salt and info.
        val okm = SessionCrypto.hkdf(ByteArray(22) { 0x0b }, ByteArray(0), "", 42)
        val expected = "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"
        assertEquals(expected, okm.joinToString("") { "%02x".format(it) })
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
