package io.github.marioalr.tpvca.documents

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.marioalr.tpvca.data.security.DataGate
import io.github.marioalr.tpvca.data.security.LabFileCache
import io.github.marioalr.tpvca.data.security.SecretTemporarilyUnavailableException
import io.github.marioalr.tpvca.data.security.SecretWrapper
import io.github.marioalr.tpvca.sync.hrana.HranaConnection
import io.github.marioalr.tpvca.sync.hrana.HranaValue
import io.github.marioalr.tpvca.sync.hrana.ResultSet
import io.github.marioalr.tpvca.sync.hrana.Snapshot
import io.github.marioalr.tpvca.sync.hrana.Statement
import io.github.marioalr.tpvca.sync.link.LabFetch
import io.github.marioalr.tpvca.sync.link.LabFileRules
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.TreeMap

/** Revisión de K4: abrir un resultado nunca tumba la app, y un borrado a la mitad no deja el archivo. */
@RunWith(AndroidJUnit4::class)
class LabFilesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dir = File(context.noBackupFilesDir, "lab-prueba")
    private val content = ByteArray(1000) { it.toByte() }
    private val sha = LabFileRules.sha256(content)
    private val gate = DataGate()

    @Before
    fun clean() {
        dir.deleteRecursively()
    }

    private open class Plain : SecretWrapper {
        override fun wrap(plain: ByteArray) = plain
        override fun unwrap(wrapped: ByteArray) = wrapped
        override fun destroy() = Unit
    }

    private object Busy : Plain() {
        override fun wrap(plain: ByteArray): ByteArray = throw SecretTemporarilyUnavailableException()
        override fun unwrap(wrapped: ByteArray): ByteArray = throw SecretTemporarilyUnavailableException()
    }

    private inner class Cloud(val beforeAnswer: () -> Unit = {}) : HranaConnection {
        var reads = 0

        private fun row(vararg pairs: Pair<String, HranaValue>) = TreeMap<String, HranaValue>(String.CASE_INSENSITIVE_ORDER).apply { putAll(pairs) }

        override fun query(statements: List<Statement>): List<ResultSet> {
            reads++
            beforeAnswer()
            return listOf(
                if (statements.single().sql == LabFileRules.STATE_SQL) {
                    ResultSet(listOf(row("SizeBytes" to HranaValue.Integer("1000"), "ChunkCount" to HranaValue.Integer("1"), "MimeType" to HranaValue.Text("application/pdf"), "CompletedAtUtc" to HranaValue.Text("x"), "EvictedAtUtc" to HranaValue.Null)))
                } else {
                    ResultSet(listOf(row("ChunkIndex" to HranaValue.Integer("0"), "Data" to HranaValue.Blob(content))))
                },
            )
        }

        override fun snapshot(before: List<Statement>, statements: List<Statement>): Snapshot = error("No se usa")
    }

    /**
     * Revisión de K7: el archivo de un resultado que se está agregando se guarda antes que su fila. Si la subida pasa justo entre los dos (aunque el archivo parezca viejo: el
     * mismo archivo ya estaba pendiente), no lo suelta a la caché, donde el LRU lo tiraría sin haberlo subido. Con la fila escrita (o fallida), el apartado se suelta.
     */
    @Test
    fun laSubidaNoSueltaElArchivoDeUnResultadoQueSeEstaAgregando() {
        val cache = LabFileCache(dir, Plain())
        val store = LabFileStore(cache, gate)
        val ticket = store.ticket()
        val written = store.save(ticket, content) { savedSha ->
            assertEquals(sha, savedSha)
            assertTrue(cache.isPending(sha))
            File(dir, "pendientes/$sha").setLastModified(System.currentTimeMillis() - 3_600_000)
            // La vuelta de la subida, en medio: todavía ninguna fila lo nombra
            store.settleOrphan(ticket, sha) { true }
            assertTrue("Apartado: sigue pendiente", cache.isPending(sha))
            "fila"
        }
        assertEquals("fila", written)
        assertTrue(cache.isPending(sha))
        // Ya sin apartar, un huérfano de verdad sí se suelta
        store.settleOrphan(ticket, sha) { true }
        assertFalse(cache.isPending(sha))
        // Si la fila falla, el apartado también se suelta
        val other = ByteArray(500) { (it * 3).toByte() }
        val otherSha = LabFileRules.sha256(other)
        runCatching { store.save(ticket, other) { error("Falló la base") } }
        assertTrue(cache.isPending(otherSha))
        store.settleOrphan(ticket, otherSha) { true }
        assertFalse(cache.isPending(otherSha))
    }

    @Test
    fun unShaQueNoLoEsDaUnMensaje() {
        val cloud = Cloud()
        val fetched = LabFiles(LabFileCache(dir, Plain()), gate) { cloud }.open("no-es-hexadecimal", "application/pdf")
        assertEquals(LabFetch.Broken, fetched)
        assertEquals(0, cloud.reads)
    }

    @Test
    fun unKeystoreOcupadoNoImpideVerlo() {
        File(dir, sha).apply { parentFile!!.mkdirs(); writeBytes(content) }
        val fetched = LabFiles(LabFileCache(dir, Busy), gate) { Cloud() }.open(sha, "application/pdf") as LabFetch.Ok
        assertArrayEquals(content, fetched.bytes)
    }

    @Test
    fun unDiscoLlenoNoImpideVerlo() {
        // La carpeta de la caché es un archivo: escribir ahí falla como un disco lleno
        dir.parentFile!!.mkdirs()
        dir.writeText("no es una carpeta")
        val fetched = LabFiles(LabFileCache(dir, Plain()), gate) { Cloud() }.open(sha, "application/pdf")
        assertTrue(fetched is LabFetch.Ok)
        dir.delete()
    }

    @Test
    fun unaUrlGuardadaQueYaNoSirveDaUnMensaje() {
        val fetched = LabFiles(LabFileCache(dir, Plain()), gate) { throw IllegalArgumentException("URL") }.open(sha, "application/pdf")
        assertTrue(fetched is LabFetch.Failed)
    }

    /** La revocación llega mientras baja: nada se guarda, ni cifrado. */
    @Test
    fun unBorradoMientrasBajaNoDejaElArchivo() {
        val cloud = Cloud { gate.close() }
        val fetched = LabFiles(LabFileCache(dir, Plain()), gate) { cloud }.open(sha, "application/pdf")
        assertTrue(fetched is LabFetch.Failed)
        assertFalse(File(dir, sha).exists())
    }
}
