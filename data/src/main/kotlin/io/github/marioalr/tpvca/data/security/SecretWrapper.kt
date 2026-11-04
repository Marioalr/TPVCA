package io.github.marioalr.tpvca.data.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Lo envuelto ya no se puede abrir: la llave del Keystore se perdió o los bytes se dañaron. */
class SecretUnreadableException(cause: Throwable? = null) : Exception("No se pudo abrir un dato guardado en este equipo.", cause)

/** Cifra y descifra con una llave que no sale del equipo. En Android es [KeystoreSecretWrapper]; en las pruebas, una llave en memoria. */
interface SecretWrapper {
    fun wrap(plain: ByteArray): ByteArray

    /** @throws SecretUnreadableException si no se puede abrir. */
    fun unwrap(wrapped: ByteArray): ByteArray

    /** Borra la llave: lo envuelto con ella ya no se podrá abrir. */
    fun destroy()
}

/**
 * AES-256-GCM con una llave del Android Keystore (plan §4.8): no se puede exportar y usa StrongBox si el equipo lo tiene. Sin pedir
 * autenticación y con `unlockedDeviceRequired=false`, para que la sincronización siga con la pantalla bloqueada: por eso el bloqueo de la
 * app es de su interfaz y no de esta llave. Formato: los 12 bytes del IV y luego el texto cifrado con su etiqueta.
 */
class KeystoreSecretWrapper(context: Context, private val alias: String) : SecretWrapper {
    private val strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

    private fun keyStore() = KeyStore.getInstance(PROVIDER).apply { load(null) }

    @Synchronized
    private fun key(): SecretKey = keyStore().getKey(alias, null) as SecretKey? ?: generate()

    private fun generate(): SecretKey {
        if (strongBox) {
            try {
                return generate(useStrongBox = true)
            } catch (_: StrongBoxUnavailableException) {
                // Lo anuncia pero no lo tiene disponible: la llave se queda en el Keystore normal
            }
        }
        return generate(useStrongBox = false)
    }

    private fun generate(useStrongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            spec.setUnlockedDeviceRequired(false)
            spec.setIsStrongBoxBacked(useStrongBox)
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
            init(spec.build())
            generateKey()
        }
    }

    override fun wrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun unwrap(wrapped: ByteArray): ByteArray = try {
        val key = keyStore().getKey(alias, null) as SecretKey? ?: throw SecretUnreadableException()
        if (wrapped.size <= IV_BYTES) throw SecretUnreadableException()
        Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, wrapped, 0, IV_BYTES))
            doFinal(wrapped, IV_BYTES, wrapped.size - IV_BYTES)
        }
    } catch (e: GeneralSecurityException) {
        throw SecretUnreadableException(e)
    } catch (e: java.security.ProviderException) {
        throw SecretUnreadableException(e)
    }

    @Synchronized
    override fun destroy() {
        keyStore().deleteEntry(alias)
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
