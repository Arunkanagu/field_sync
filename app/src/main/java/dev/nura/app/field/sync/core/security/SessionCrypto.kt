package dev.nura.app.field.sync.core.security

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Keys for one authenticated session, derived from the pairing secret and a fresh ECDH exchange. */
class SessionKeys(
    val authKey: ByteArray,
    val clientToServerKey: ByteArray,
    val serverToClientKey: ByteArray,
)

/**
 * Primitives used by the pairing and session handshake (only standard JCA algorithms, so the
 * same code runs on Android and in JVM unit tests):
 *
 * - ECDH on P-256 with keys that are fresh for every session (forward secrecy);
 * - HKDF-SHA256 (RFC 5869) for key derivation;
 * - HMAC-SHA256 for key confirmation and mutual authentication.
 */
object SessionCrypto {

    const val NONCE_BYTES = 32
    const val SECRET_BYTES = 32
    private const val HMAC = "HmacSHA256"
    private val random = SecureRandom()

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    fun generateEphemeralKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()

    /** Decodes and sanity-checks a peer's X.509 public key. Throws [IllegalArgumentException] if invalid. */
    fun decodePublicKey(encoded: ByteArray): PublicKey {
        val key = runCatching { KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded)) }
            .getOrElse { throw IllegalArgumentException("invalid public key", it) }
        require(key is ECPublicKey && key.params.curve.field.fieldSize == 256) { "unsupported public key" }
        return key
    }

    fun ecdh(privateKey: PrivateKey, peerPublicKey: PublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").run {
            init(privateKey)
            doPhase(peerPublicKey, true)
            generateSecret()
        }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance(HMAC).run {
            init(SecretKeySpec(key, HMAC))
            doFinal(data)
        }

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: String, length: Int): ByteArray {
        require(length in 1..255 * 32)
        val prk = hmac(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val output = ByteArrayOutputStream()
        var previous = ByteArray(0)
        var counter = 1
        while (output.size() < length) {
            previous = hmac(prk, previous + info.toByteArray(Charsets.UTF_8) + counter.toByte())
            output.write(previous)
            counter++
        }
        return output.toByteArray().copyOf(length)
    }

    /** SHA-256 over length-prefixed parts, so no two different part lists hash the same input. */
    fun transcriptHash(vararg parts: ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.write("fieldsync-handshake-v1".toByteArray(Charsets.UTF_8))
            parts.forEach { part ->
                out.writeInt(part.size)
                out.write(part)
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
    }

    /**
     * Six-digit short authentication string shown on both screens during pairing. A
     * man-in-the-middle would cause the two devices to show different codes.
     */
    fun pairingCode(sharedSecret: ByteArray, transcript: ByteArray): String {
        val bytes = hkdf(sharedSecret, transcript, "fieldsync pairing code", 4)
        val value = (ByteBuffer.wrap(bytes).int.toLong() and 0xFFFFFFFFL) % 1_000_000L
        return value.toString().padStart(6, '0')
    }

    fun pairingConfirmKey(sharedSecret: ByteArray, transcript: ByteArray): ByteArray =
        hkdf(sharedSecret, transcript, "fieldsync pairing confirm", SECRET_BYTES)

    /** Long-term secret stored for a peer once both users confirmed the pairing code. */
    fun pairingSecret(sharedSecret: ByteArray, transcript: ByteArray): ByteArray =
        hkdf(sharedSecret, transcript, "fieldsync pairing secret", SECRET_BYTES)

    fun deriveSessionKeys(pairingSecret: ByteArray, ephemeralShared: ByteArray, transcript: ByteArray): SessionKeys {
        val material = hkdf(pairingSecret + ephemeralShared, transcript, "fieldsync session keys", SECRET_BYTES * 3)
        return SessionKeys(
            authKey = material.copyOfRange(0, 32),
            clientToServerKey = material.copyOfRange(32, 64),
            serverToClientKey = material.copyOfRange(64, 96),
        )
    }

    fun macMatches(expected: ByteArray, actual: ByteArray?): Boolean =
        actual != null && MessageDigest.isEqual(expected, actual)
}
