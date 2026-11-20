package dev.nura.app.field.sync.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Encrypts small secrets (peer pairing secrets) at rest. */
interface KeyWrapper {
    fun wrap(secret: ByteArray): String
    fun unwrap(wrapped: String): ByteArray
}

/**
 * Wraps secrets with a non-exportable AES-256-GCM key held in the Android Keystore.
 * Stored format: base64(`[12-byte IV][ciphertext + tag]`).
 */
@Singleton
class KeystoreKeyWrapper @Inject constructor() : KeyWrapper {

    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getDecoder()

    override fun wrap(secret: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return encoder.encodeToString(cipher.iv + cipher.doFinal(secret))
    }

    override fun unwrap(wrapped: String): ByteArray {
        val bytes = decoder.decode(wrapped)
        require(bytes.size > IV_BYTES) { "Corrupt wrapped secret." }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_BYTES))
        }
        return cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES)
    }

    @Synchronized
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "fieldsync_peer_secret_wrapping_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
