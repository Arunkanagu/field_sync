package dev.nura.app.field.sync.core.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordHasherTest {

    private val hasher = PasswordHasher(iterations = 1_000)

    @Test
    fun verify_acceptsCorrectPassword() {
        val encoded = hasher.hash("correct horse")
        assertTrue(hasher.verify("correct horse", encoded))
    }

    @Test
    fun verify_rejectsWrongPassword() {
        val encoded = hasher.hash("correct horse")
        assertFalse(hasher.verify("correct horsf", encoded))
    }

    @Test
    fun hash_usesUniqueSaltPerCall() {
        assertNotEquals(hasher.hash("same password"), hasher.hash("same password"))
    }

    @Test
    fun hash_neverContainsPlainTextPassword() {
        assertFalse(hasher.hash("plaintext123").contains("plaintext123"))
    }

    @Test
    fun verify_rejectsMalformedEncodings() {
        listOf("", "garbage", "pbkdf2_sha256:abc:salt:hash", "md5:1000:AAAA:AAAA", "pbkdf2_sha256:1000:!!!:AAAA")
            .forEach { assertFalse(it, hasher.verify("password", it)) }
    }

    @Test
    fun needsRehash_whenStoredIterationsAreLower() {
        val weak = PasswordHasher(iterations = 500).hash("password")
        assertTrue(hasher.needsRehash(weak))
        assertFalse(hasher.needsRehash(hasher.hash("password")))
    }
}
