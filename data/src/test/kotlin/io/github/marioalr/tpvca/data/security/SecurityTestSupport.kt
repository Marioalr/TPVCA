package io.github.marioalr.tpvca.data.security

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Robolectric no trae el AndroidKeyStore: una llave AES-256-GCM en memoria con el mismo formato que [KeystoreSecretWrapper], que se
 * comprueba en un equipo (`src/androidTest`).
 */
class MemorySecretWrapper : SecretWrapper {
    private var key: SecretKey? = null
    var destroyed = 0

    /** El Keystore no responde esta vez. */
    var unavailable = false

    private fun key() = key ?: KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }

    override fun wrap(plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        return iv + cipher.doFinal(plain)
    }

    override fun unwrap(wrapped: ByteArray): ByteArray {
        if (unavailable) throw SecretTemporarilyUnavailableException()
        val key = key ?: throw SecretUnreadableException()
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, wrapped, 0, 12)) }
            return cipher.doFinal(wrapped, 12, wrapped.size - 12)
        } catch (e: AEADBadTagException) {
            throw SecretUnreadableException(e)
        }
    }

    override fun destroy() {
        destroyed++
        key = null
    }
}
