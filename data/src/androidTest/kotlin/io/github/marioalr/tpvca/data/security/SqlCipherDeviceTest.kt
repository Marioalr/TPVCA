package io.github.marioalr.tpvca.data.security

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.marioalr.tpvca.data.SqlCipher
import io.github.marioalr.tpvca.domain.devices.DeviceRole
import io.github.marioalr.tpvca.sync.engine.StoreChanges
import io.github.marioalr.tpvca.sync.link.DeviceCredential
import io.github.marioalr.tpvca.sync.link.StoredCredential
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import javax.crypto.SecretKey

/**
 * Lo que la JVM no puede comprobar: SQLCipher con Room y el Keystore de verdad. No corre en la CI: `./gradlew :data:connectedDebugAndroidTest` con
 * un equipo o un emulador conectado.
 */
@RunWith(AndroidJUnit4::class)
class SqlCipherDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "prueba-cifrada.db"
    private val keyPath = File(context.noBackupFilesDir, "prueba/llave.bin")
    private val keyAlias = "tpvca.prueba.base"
    private val assets = File(context.filesDir, "prueba-membrete")

    private fun database() =
        EncryptedLocalDatabase(context, name, WrappedFile(keyPath, KeystoreSecretWrapper(context, keyAlias)), assets, SqlCipher::factory)

    @After
    fun clean() {
        database().erase()
        KeystoreSecretWrapper(context, "tpvca.prueba.credencial").destroy()
        File(context.noBackupFilesDir, "prueba").deleteRecursively()
    }

    @Test
    fun laBaseEnElDiscoNoSeLeeSinLaFrase() {
        val secret = "EXPEDIENTE-DE-PRUEBA-1234567890"
        database().store().apply(StoreChanges(meta = mapOf("clave" to secret)))
        val file = context.getDatabasePath(name)
        assertTrue(file.length() > 0)

        val raw = file.readBytes()
        assertFalse("Un archivo de SQLite sin cifrar empieza con este texto", String(raw, 0, 15, Charsets.ISO_8859_1) == "SQLite format 3")
        assertFalse(String(raw, Charsets.ISO_8859_1).contains(secret))
        listOf("-wal", "-journal").map { File(file.path + it) }.filter { it.exists() }.forEach {
            assertFalse(it.name, String(it.readBytes(), Charsets.ISO_8859_1).contains(secret))
        }
    }

    @Test
    fun sqliteDelSistemaNoLaPuedeAbrir() {
        database().store().apply(StoreChanges(meta = mapOf("clave" to "valor")))
        assertThrows(SQLiteException::class.java) {
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use {
                it.rawQuery("SELECT count(*) FROM DbMeta", null).use { cursor -> cursor.moveToFirst() }
            }
        }
    }

    @Test
    fun otroProcesoConLaMismaLlaveLaAbreYLeeTodo() {
        database().store().apply(StoreChanges(meta = mapOf("clave" to "valor")))
        assertEquals("valor", database().store().meta("clave"))
    }

    @Test
    fun borrarQuitaLaBaseYLaLlave() {
        val database = database()
        database.store().apply(StoreChanges(meta = mapOf("clave" to "valor")))
        database.erase()
        assertFalse(context.getDatabasePath(name).exists())
        assertFalse(keyPath.exists())
        assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(keyAlias))
        assertNull(database.store().meta("clave"))
    }

    @Test
    fun laLlaveDelKeystoreNoSeExporta() {
        val wrapper = KeystoreSecretWrapper(context, keyAlias)
        val wrapped = wrapper.wrap("secreto".toByteArray())
        assertEquals("secreto", String(wrapper.unwrap(wrapped)))
        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(keyAlias, null) as SecretKey
        assertNull("El material de la llave no sale del Keystore", key.encoded)
    }

    @Test
    fun loEnvueltoNoSeAbreSinSuLlaveNiDanado() {
        val wrapper = KeystoreSecretWrapper(context, keyAlias)
        val wrapped = wrapper.wrap("secreto".toByteArray())
        val damaged = wrapped.copyOf().also { it[it.size - 1] = (it.last() + 1).toByte() }
        assertThrows(SecretUnreadableException::class.java) { wrapper.unwrap(damaged) }
        wrapper.destroy()
        assertThrows(SecretUnreadableException::class.java) { wrapper.unwrap(wrapped) }
    }

    @Test
    fun laCredencialVaEnvueltaConElKeystore() {
        val file = File(context.noBackupFilesDir, "prueba/credencial.bin")
        val vault = EncryptedCredentialVault(WrappedFile(file, KeystoreSecretWrapper(context, "tpvca.prueba.credencial")))
        vault.save(DeviceCredential("https://consultorio-ejemplo.turso.io", "token-de-prueba", "", "equipo", DeviceRole.AndroidMobileAdmin))
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("token-de-prueba"))
        assertEquals("token-de-prueba", (vault.load() as StoredCredential.Present).credential.token)
    }
}
