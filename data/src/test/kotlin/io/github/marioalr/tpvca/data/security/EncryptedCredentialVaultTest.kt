package io.github.marioalr.tpvca.data.security

import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.marioalr.tpvca.domain.devices.DeviceRole
import io.github.marioalr.tpvca.sync.link.DeviceCredential
import io.github.marioalr.tpvca.sync.link.StoredCredential
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.InvalidKeyException
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException

@RunWith(AndroidJUnit4::class)
class EncryptedCredentialVaultTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val wrapper = MemorySecretWrapper()
    private val file: File by lazy { File(folder.root, "seguridad/credencial.bin") }
    private val vault by lazy { EncryptedCredentialVault(WrappedFile(file, wrapper)) }
    private val credential = DeviceCredential("https://consultorio-ejemplo.turso.io", "token-de-prueba", "Peña", "equipo", DeviceRole.AndroidTablet, "clinica")

    @Test
    fun sinArchivoNoTocaLaLlave() {
        val untouchable = object : SecretWrapper {
            override fun wrap(plain: ByteArray) = error("no se debe usar")
            override fun unwrap(wrapped: ByteArray) = error("no se debe usar")
            override fun destroy() = error("no se debe usar")
        }
        assertEquals(StoredCredential.Empty, EncryptedCredentialVault(WrappedFile(file, untouchable)).load())
    }

    @Test
    fun guardaYLee() {
        vault.save(credential)
        val back = (vault.load() as StoredCredential.Present).credential
        assertEquals("token-de-prueba", back.token)
        assertEquals("equipo", back.deviceId)
        assertEquals(DeviceRole.AndroidTablet, back.requestedRole)
    }

    @Test
    fun enElDiscoNoSeLeeNada() {
        vault.save(credential)
        val raw = String(file.readBytes(), Charsets.ISO_8859_1)
        for (secret in listOf("token-de-prueba", "consultorio-ejemplo", "equipo", "Pe")) {
            assertFalse(secret, raw.contains(secret) && secret.length > 2)
        }
        assertFalse("No queda el temporal", File(file.parentFile, "credencial.bin.tmp").exists())
    }

    @Test
    fun dosVecesLoMismoNoSeVenIgual() {
        vault.save(credential)
        val first = file.readBytes()
        vault.save(credential)
        assertFalse("El IV es nuevo cada vez", first.contentEquals(file.readBytes()))
    }

    @Test
    fun unArchivoDanadoOLaLlavePerdidaNoSeAbren() {
        vault.save(credential)
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes.last() + 1).toByte()
        file.writeBytes(bytes)
        assertEquals(StoredCredential.Unreadable, vault.load())

        vault.save(credential)
        wrapper.destroy()
        assertEquals(StoredCredential.Unreadable, vault.load())
    }

    @Test
    fun unErrorPasajeroDelKeystoreNoEsIlegibleYNoSeBorraNada() {
        vault.save(credential)
        wrapper.unavailable = true
        assertThrows(SecretTemporarilyUnavailableException::class.java) { vault.load() }
        assertTrue(file.exists())
        assertEquals(0, wrapper.destroyed)
        wrapper.unavailable = false
        assertEquals("token-de-prueba", (vault.load() as StoredCredential.Present).credential.token)
    }

    /** Solo es definitivo lo que no se arregla reintentando. */
    @Test
    fun loDefinitivoYLoPasajeroSeDistinguen() {
        assertTrue(KeystoreSecretWrapper.classify(AEADBadTagException()) is SecretUnreadableException)
        assertTrue(KeystoreSecretWrapper.classify(KeyPermanentlyInvalidatedException()) is SecretUnreadableException)
        for (transient in listOf(KeyStoreException("ocupado"), UnrecoverableKeyException("x"), ProviderException("sin respuesta"), IOException("x"), InvalidKeyException("x"))) {
            assertTrue("$transient", KeystoreSecretWrapper.classify(transient) is SecretTemporarilyUnavailableException)
        }
    }

    @Test
    fun borrarQuitaElArchivoYLaLlave() {
        vault.save(credential)
        vault.clear()
        assertFalse(file.exists())
        assertEquals(1, wrapper.destroyed)
        assertEquals(StoredCredential.Empty, vault.load())
        assertTrue("Después se puede guardar otra", runCatching { vault.save(credential) }.isSuccess)
    }
}
