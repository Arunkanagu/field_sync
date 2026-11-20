package dev.nura.app.field.sync.domain.auth

import java.util.Locale

object CredentialRules {

    const val MIN_PASSWORD_LENGTH = 8

    private val usernamePattern = Regex("[a-z0-9._-]{3,32}")

    fun normalizeUsername(raw: String): String = raw.trim().lowercase(Locale.ROOT)

    /** Returns an error message, or null if [normalizedUsername] is valid. */
    fun usernameError(normalizedUsername: String): String? =
        if (usernamePattern.matches(normalizedUsername)) {
            null
        } else {
            "Username must be 3–32 characters using letters, digits, '.', '_' or '-'."
        }

    /** Returns an error message, or null if [password] is acceptable. */
    fun passwordError(password: String): String? =
        if (password.length >= MIN_PASSWORD_LENGTH) {
            null
        } else {
            "Password must be at least $MIN_PASSWORD_LENGTH characters."
        }

    fun requireValid(normalizedUsername: String, password: String) {
        val error = usernameError(normalizedUsername) ?: passwordError(password)
        require(error == null) { error.orEmpty() }
    }
}
