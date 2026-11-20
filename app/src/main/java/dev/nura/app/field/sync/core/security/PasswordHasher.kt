package dev.nura.app.field.sync.core.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject

/**
 * Salted PBKDF2-HMAC-SHA256 password hashing.
 *
 * Encoded format: `pbkdf2_sha256:<iterations>:<base64 salt>:<base64 hash>`. Storing the iteration
 * count lets [needsRehash] upgrade old hashes on the next successful login.
 */
class PasswordHasher internal constructor(private val iterations: Int) {

    @Inject
    constructor() : this(DEFAULT_ITERATIONS)

    private val random = SecureRandom()
    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getDecoder()

    fun hash(password: String): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val hash = derive(password, salt, iterations)
        return listOf(ALGORITHM_ID, iterations.toString(), encoder.encodeToString(salt), encoder.encodeToString(hash))
            .joinToString(SEPARATOR)
    }

    fun verify(password: String, encoded: String): Boolean {
        val parts = encoded.split(SEPARATOR)
        if (parts.size != 4 || parts[0] != ALGORITHM_ID) return false
        val storedIterations = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return false
        val salt = runCatching { decoder.decode(parts[2]) }.getOrNull() ?: return false
        val expected = runCatching { decoder.decode(parts[3]) }.getOrNull() ?: return false
        return MessageDigest.isEqual(expected, derive(password, salt, storedIterations))
    }

    fun needsRehash(encoded: String): Boolean {
        val parts = encoded.split(SEPARATOR)
        val storedIterations = parts.getOrNull(1)?.toIntOrNull() ?: return true
        return parts[0] != ALGORITHM_ID || storedIterations < iterations
    }

    private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val chars = password.toCharArray()
        val spec = PBEKeySpec(chars, salt, iterations, KEY_LENGTH_BITS)
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
    }

    companion object {
        const val DEFAULT_ITERATIONS = 210_000
        private const val ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val ALGORITHM_ID = "pbkdf2_sha256"
        private const val SALT_BYTES = 16
        private const val KEY_LENGTH_BITS = 256
        private const val SEPARATOR = ":"
    }
}
