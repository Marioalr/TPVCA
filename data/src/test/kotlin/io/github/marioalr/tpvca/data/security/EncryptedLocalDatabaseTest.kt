package io.github.marioalr.tpvca.data.security

import android.content.Context
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.marioalr.tpvca.sync.engine.StoreChanges
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * La llave y el ciclo de vida de la base, con SQLite en lugar de SQLCipher: la biblioteca nativa no carga en la JVM, así que lo que aquí no se
 * comprueba es que el archivo quede cifrado. Eso lo revisa `SqlCipherDeviceTest` en un equipo.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedLocalDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val keyPath = File(context.noBackupFilesDir, "seguridad/llave-base.bin")
    private val assets = File(context.filesDir, "membrete")
    private val wrapper = MemorySecretWrapper()
    private val passphrases = ArrayList<ByteArray>()

    private fun database() = EncryptedLocalDatabase(context, "prueba.db", WrappedFile(keyPath, wrapper), assets, {
        passphrases += it
        FrameworkSQLiteOpenHelperFactory()
    })

    private fun EncryptedLocalDatabase.put(key: String, value: String) = store().apply(StoreChanges(meta = mapOf(key to value)))

    @Test
    fun laFraseSon32BytesAleatoriosEnvueltos() {
        database().store()
        val passphrase = passphrases.single()
        assertEquals(32, passphrase.size)
        assertArrayEquals(passphrase, wrapper.unwrap(keyPath.readBytes()))
        assertFalse("En el disco no está en claro", String(keyPath.readBytes(), Charsets.ISO_8859_1).contains(String(passphrase, Charsets.ISO_8859_1)))
    }

    @Test
    fun alReabrirUsaLaMismaFraseYLaMismaBase() {
        database().put("clave", "valor")
        assertEquals("valor", database().store().meta("clave"))
        assertArrayEquals(passphrases[0], passphrases[1])
    }

    @Test
    fun abrirDosVecesEsLaMismaBase() {
        val database = database()
        assertTrue(database.store() === database.store())
        assertEquals(1, passphrases.size)
    }

    @Test
    fun borrarQuitaElArchivoLaLlaveYElMembrete() {
        val database = database()
        database.put("clave", "valor")
        assets.mkdirs()
        File(assets, "escudo.png").writeText("imagen")
        assertTrue(context.getDatabasePath("prueba.db").exists())

        database.erase()

        assertFalse(context.getDatabasePath("prueba.db").exists())
        assertFalse(keyPath.exists())
        assertFalse(assets.exists())
        assertEquals(1, wrapper.destroyed)
    }

    @Test
    fun despuesDeBorrarEmpiezaConOtraFraseYVacia() {
        val database = database()
        database.put("clave", "valor")
        database.erase()
        assertNull(database.store().meta("clave"))
        assertFalse(passphrases[0].contentEquals(passphrases[1]))
    }

    @Test
    fun sinPoderAbrirLaLlaveEmpiezaDeNuevoConUnaBaseVacia() {
        database().put("clave", "valor")
        // El Keystore perdió la llave: lo que hay en la base ya no se podría leer
        wrapper.destroy()
        val reopened = database()
        assertNull(reopened.store().meta("clave"))
        assertNotNull(keyPath.takeIf { it.exists() })
        assertFalse(passphrases[0].contentEquals(passphrases[1]))
    }

    @Test
    fun unErrorPasajeroDelKeystoreNoBorraLaBase() {
        database().put("clave", "valor")
        wrapper.unavailable = true
        assertThrows(SecretTemporarilyUnavailableException::class.java) { database().store() }
        assertTrue(context.getDatabasePath("prueba.db").exists())
        assertTrue(keyPath.exists())
        wrapper.unavailable = false
        assertEquals("valor", database().store().meta("clave"))
    }

    @Test
    fun laLlaveVaEnLaCarpetaQueAndroidNoRespalda() {
        assertTrue(keyPath.path.startsWith(context.noBackupFilesDir.path))
    }

    /** Quien se quedó con el objeto de antes no vuelve a abrir la base ni crea otra con una llave nueva. */
    @Test
    fun unaReferenciaDeAntesNoVuelveAAbrirLaBaseDespuesDeBorrar() {
        val database = database()
        val stale = database.store()
        stale.apply(StoreChanges(meta = mapOf("a" to "b")))
        assertTrue(database.exists())
        database.erase()
        assertFalse(database.exists())
        assertThrows(IllegalStateException::class.java) { stale.meta("a") }
        assertFalse("No se creó nada", database.exists())
    }
}
