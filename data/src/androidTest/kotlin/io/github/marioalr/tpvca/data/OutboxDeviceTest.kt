package io.github.marioalr.tpvca.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.marioalr.tpvca.data.security.EncryptedLocalDatabase
import io.github.marioalr.tpvca.data.security.KeystoreSecretWrapper
import io.github.marioalr.tpvca.data.security.WrappedFile
import io.github.marioalr.tpvca.sync.engine.OutboxMark
import io.github.marioalr.tpvca.sync.engine.RowWrite
import io.github.marioalr.tpvca.sync.engine.StoreChanges
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** La cola de salida con SQLCipher de verdad: la transacción, sobrevivir a cerrar la base (el proceso) y que dos hilos no se crucen. */
@RunWith(AndroidJUnit4::class)
class OutboxDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "cola-${System.nanoTime()}.db"
    private val keyFile = File(context.noBackupFilesDir, "$name.llave")
    private val assets = File(context.cacheDir, "$name-membrete")
    private val wrapper = KeystoreSecretWrapper(context, "tpvca.prueba.cola")

    private fun database() = EncryptedLocalDatabase(context, name, WrappedFile(keyFile, wrapper), assets, SqlCipher::factory)

    private lateinit var open: EncryptedLocalDatabase

    @Before
    fun clean() {
        keyFile.delete()
        context.deleteDatabase(name)
        open = database()
    }

    @After
    fun erase() = open.erase()

    private fun category(id: String) = RowWrite(
        "Categories", id,
        mapOf("SyncId" to id, "UpdatedAtUtc" to "2026-11-03 10:00:00.0000000", "SyncedAtUtc" to "", "OriginDeviceId" to null, "Description" to null, "Icon" to "", "Name" to "C"),
        isNew = true,
    )

    private fun mark(id: String) = OutboxMark("Category", id, "2026-11-03 10:00:00.0000000")

    @Test
    fun laColaYSuEscrituraSeDeshacenJuntasYSobrevivenACerrar() {
        val store = open.store()
        assertThrows(IllegalStateException::class.java) {
            store.transaction {
                store.apply(StoreChanges(writes = listOf(category("a")), outbox = listOf(mark("a"))))
                error("Se cayó")
            }
        }
        assertEquals(0, store.pendingChanges())
        assertTrue(store.existing("Categories", listOf("a")).isEmpty())

        store.apply(StoreChanges(writes = listOf(category("b")), outbox = listOf(mark("b"))))
        open.database().close()

        // Otra instancia, como otro proceso: la misma llave del Keystore
        open = database()
        assertEquals(1, open.store().pendingChanges())
        assertEquals("b", open.store().outbox().single().syncId)
        assertEquals(setOf("b"), open.store().existing("Categories", listOf("b")))

        // Los argumentos numéricos con SQLCipher: vaciar la cola hasta un número y quitar lo guardado
        val reopened = open.store()
        val seq = reopened.outbox().single().seq
        val saved = io.github.marioalr.tpvca.sync.engine.SavedChange(0, "este-equipo", "g", "Category", "b", "row", "{}", 3, 21, "t")
        reopened.apply(StoreChanges(outboxClearedUpTo = seq, saved = listOf(saved)))
        assertEquals(0, reopened.pendingChanges())
        val id = reopened.savedChanges().single().id
        reopened.apply(StoreChanges(savedRemoved = listOf(id)))
        assertTrue(reopened.savedChanges().isEmpty())
    }

    @Test
    fun unaEscrituraEnOtroHiloEsperaALaTransaccion() {
        val store = open.store()
        val inside = CountDownLatch(1)
        var wroteDuring = false
        val other = thread {
            inside.await(5, TimeUnit.SECONDS)
            store.apply(StoreChanges(writes = listOf(category("otro")), outbox = listOf(mark("otro"))))
            wroteDuring = true
        }
        store.transaction {
            inside.countDown()
            Thread.sleep(300)
            assertFalse("El otro hilo no entró a media transacción", wroteDuring)
            store.apply(StoreChanges(writes = listOf(category("este")), outbox = listOf(mark("este"))))
        }
        other.join(5000)
        assertTrue(wroteDuring)
        assertEquals(2, store.pendingChanges())
    }

    /** La expresión del teléfono con lookbehind: K4 encontró una que solo compilaba en la JVM. */
    @Test
    fun lasReglasNuevasCompilanEnAndroid() {
        val rules = io.github.marioalr.tpvca.domain.patients.DuplicatePatientRules
        assertEquals("5512345678", rules.normalizedPhone("+52 55 1234 5678 ext. 12"))
        assertEquals("jose perez", rules.normalizedName("José   Pérez"))
        assertEquals("EXP-2026-T1", io.github.marioalr.tpvca.sync.rules.FolioFactory.parseLettered("EXP-2026-T1-004")?.let { "${it.series}-${it.deviceCode}" })
        assertEquals("2026-10-14 15:00:10.0000000", io.github.marioalr.tpvca.sync.rules.PatientFieldStamps.parseStamp("2026-10-14 15:00:10.0000000"))
    }
}
