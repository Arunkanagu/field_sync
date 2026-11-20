package dev.nura.app.field.sync.core.security

import dev.nura.app.field.sync.core.transport.AuthenticationFailedException
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM for one session. Frames are `[8-byte counter][ciphertext + 16-byte tag]`.
 *
 * - Each direction has its own key, so the counter-based nonce is never reused under a key.
 * - The receiver requires counters to arrive strictly in order, which rejects replayed,
 *   reordered or dropped frames.
 * - The counter is authenticated as associated data.
 */
class SessionCipher(sendKey: ByteArray, receiveKey: ByteArray) {

    private val sendKey = SecretKeySpec(sendKey.copyOf(), "AES")
    private val receiveKey = SecretKeySpec(receiveKey.copyOf(), "AES")
    private var sendCounter = 0L
    private var receiveCounter = 0L

    init {
        require(sendKey.size == 32 && receiveKey.size == 32) { "AES-256 keys required" }
    }

    fun seal(plaintext: ByteArray): ByteArray {
        val counter = sendCounter++
        val header = ByteBuffer.allocate(COUNTER_BYTES).putLong(counter).array()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, sendKey, GCMParameterSpec(TAG_BITS, nonce(counter)))
            updateAAD(header)
        }
        return header + cipher.doFinal(plaintext)
    }

    fun open(frame: ByteArray): ByteArray {
        if (frame.size < COUNTER_BYTES + TAG_BITS / 8) throw AuthenticationFailedException("Received a truncated message.")
        val header = frame.copyOfRange(0, COUNTER_BYTES)
        val counter = ByteBuffer.wrap(header).long
        if (counter != receiveCounter) throw AuthenticationFailedException("Received a replayed or out-of-order message.")
        val plaintext = try {
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, receiveKey, GCMParameterSpec(TAG_BITS, nonce(counter)))
                updateAAD(header)
                doFinal(frame, COUNTER_BYTES, frame.size - COUNTER_BYTES)
            }
        } catch (_: GeneralSecurityException) {
            throw AuthenticationFailedException("A message failed its integrity check.")
        }
        receiveCounter++
        return plaintext
    }

    private fun nonce(counter: Long): ByteArray = ByteBuffer.allocate(NONCE_BYTES).putInt(0).putLong(counter).array()

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val NONCE_BYTES = 12
        const val COUNTER_BYTES = 8
    }
}
