package io.github.marioalr.tpvca.documents

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.marioalr.tpvca.data.security.DataGate
import io.github.marioalr.tpvca.data.security.DataGateClosedException
import io.github.marioalr.tpvca.data.security.LabFileCache
import io.github.marioalr.tpvca.data.security.SecretWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Al revocar o limpiar el equipo no queda nada de lo compartido, y la intención solo lleva el archivo. */
@RunWith(AndroidJUnit4::class)
class DeviceFilesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private object NoWrap : SecretWrapper {
        var destroyed = false
        override fun wrap(plain: ByteArray) = plain
        override fun unwrap(wrapped: ByteArray) = wrapped
        override fun destroy() {
            destroyed = true
        }
    }

    @Test
    fun borrarSeLlevaLoCompartidoLaCacheDeLaboratorioYSuLlave() {
        val documents = SharedDocuments(context)
        val shared = documents.write(documents.ticket(), "Receta.pdf") { it.writeText("receta") }
        val viewed = documents.writeView(documents.ticket(), "Receta.pdf") { it.writeText("receta") }
        val lab = File(context.noBackupFilesDir, "laboratorio-prueba")
        val cache = LabFileCache(lab, NoWrap)
        cache.write("ab", byteArrayOf(1))
        val keep = File(context.noBackupFilesDir, "seguridad-prueba").apply { mkdirs(); resolve("credencial.bin").writeText("x") }
        val draft = File(context.filesDir, "borrador.txt").apply { writeText("x") }
        assertTrue(shared.exists())

        DeviceFiles(context, cache).erase()

        assertFalse("Lo compartido", shared.exists())
        assertFalse("Lo que se estaba viendo", viewed.exists())
        assertFalse(documents.folder.exists())
        assertFalse("La caché de laboratorio", lab.exists())
        assertTrue("Su llave", NoWrap.destroyed)
        assertFalse(draft.exists())
        assertTrue("La credencial la borra LinkFlow, no esto", keep.resolve("credencial.bin").exists())
    }

    /** WorkManager tiene su base abierta y no lleva datos: si se borra debajo, la biblioteca truena. */
    @Test
    fun borrarNoTocaLaBaseDeWorkManager() {
        val work = context.getDatabasePath("androidx.work.workdb").also { it.parentFile!!.mkdirs(); it.writeText("x") }
        val other = context.getDatabasePath("datatransport.db").also { it.writeText("x") }
        DeviceFiles(context, LabFileCache(File(context.noBackupFilesDir, "lab-prueba"), NoWrap)).erase()
        assertTrue(work.exists())
        assertFalse(other.exists())
        work.delete()
    }

    @Test
    fun loCompartidoSeBorraPorAntiguedad() {
        var now = 1_000_000_000_000L
        val documents = SharedDocuments(context) { now }
        val file = documents.write(documents.ticket(), "Ticket.pdf") { it.writeText("t") }
        file.parentFile!!.setLastModified(now)
        documents.purgeOld()
        assertTrue(file.exists())
        now += SharedDocuments.MAX_AGE_MILLIS + 1
        documents.purgeOld()
        assertFalse(file.exists())
    }

    @Test
    fun laIntencionSoloLlevaElArchivoConPermisoDeLectura() {
        val documents = SharedDocuments(context)
        val file = documents.write(documents.ticket(), "Receta.pdf") { it.writeText("x") }
        val intent = documents.shareIntent(file, "application/pdf")
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("application/pdf", intent.type)
        assertEquals(setOf(Intent.EXTRA_STREAM), intent.extras!!.keySet())
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertNull(intent.getStringExtra(Intent.EXTRA_TEXT))
        val uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)!!
        assertEquals("content", uri.scheme)
        assertEquals(context.packageName + SharedDocuments.AUTHORITY_SUFFIX, uri.authority)
        assertTrue(uri.path!!.startsWith("/compartidos/"))
        assertTrue(uri.path!!.endsWith("/Receta.pdf"))

        // En la misma prueba: FileProvider guarda sus rutas en un estático, y cada prueba de Robolectric tiene otra carpeta
        val outside = File(context.filesDir, "tpvca.db").apply { writeText("x") }
        assertTrue("Fuera de compartidos no se puede", runCatching { documents.shareIntent(outside, "application/pdf") }.exceptionOrNull() is IllegalArgumentException)
        val otherCache = File(context.cacheDir, "otra.pdf").apply { writeText("x") }
        assertTrue("Tampoco en el resto de la caché", runCatching { documents.shareIntent(otherCache, "application/pdf") }.exceptionOrNull() is IllegalArgumentException)
        val viewed = documents.writeView(documents.ticket(), "Receta.pdf") { it.writeText("x") }
        assertTrue("Lo que se ve tampoco sale por el proveedor", runCatching { documents.shareIntent(viewed, "application/pdf") }.exceptionOrNull() is IllegalArgumentException)
        val copy = documents.shareCopy(documents.ticket(), viewed)
        documents.deleteView(viewed)
        assertFalse(viewed.exists())
        assertTrue("Lo compartido sobrevive a cerrar la vista", copy.exists())
        assertEquals("application/pdf", documents.shareIntent(copy, "application/pdf").type)
    }

    /** Revisión de K4: viene una revocación mientras baja o se dibuja un documento. */
    @Test
    fun lasEscriturasFallanDespuesDeEmpezarElBorrado() {
        val gate = DataGate()
        val documents = SharedDocuments(context, gate)
        val ticket = documents.ticket()
        gate.close()
        DeviceFiles(context, LabFileCache(File(context.noBackupFilesDir, "lab-orden"), NoWrap)).erase()
        gate.reopen()
        assertTrue(runCatching { documents.write(ticket, "Receta.pdf") { it.writeText("x") } }.exceptionOrNull() is DataGateClosedException)
        assertTrue(runCatching { documents.writeView(ticket, "Receta.pdf") { it.writeText("x") } }.exceptionOrNull() is DataGateClosedException)
        assertFalse("Nada quedó en la caché", documents.folder.exists())
        assertFalse(documents.viewFolder.exists())
    }

    /** El orden con una escritura a la mitad: el borrado la espera y después se la lleva. */
    @Test
    fun elBorradoEsperaAlDocumentoAMediasYSeLoLleva() {
        val gate = DataGate()
        val documents = SharedDocuments(context, gate)
        val writing = CountDownLatch(1)
        val finish = CountDownLatch(1)
        var written: File? = null
        val writer = thread {
            written = documents.writeView(documents.ticket(), "Resultado.pdf") {
                it.writeText("en claro")
                writing.countDown()
                finish.await(5, TimeUnit.SECONDS)
            }
        }
        assertTrue(writing.await(5, TimeUnit.SECONDS))
        val wipe = thread {
            gate.close()
            DeviceFiles(context, LabFileCache(File(context.noBackupFilesDir, "lab-orden2"), NoWrap)).erase()
        }
        wipe.join(200)
        assertTrue("Sigue esperando", wipe.isAlive)
        finish.countDown()
        writer.join(5000)
        wipe.join(5000)
        assertFalse(written!!.exists())
        assertFalse(documents.viewFolder.exists())
    }

    @Test
    fun loQueFallaANivelDeDiscoNoDejaLaMitad() {
        val documents = SharedDocuments(context)
        val failure = runCatching {
            documents.writeView(documents.ticket(), "Receta.pdf") {
                it.writeText("mitad")
                throw java.io.IOException("Sin espacio")
            }
        }
        assertTrue(failure.isFailure)
        assertTrue(documents.viewFolder.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun alIrseLaAppSeBorraLoQueSeVeia() {
        val documents = SharedDocuments(context)
        val viewed = documents.writeView(documents.ticket(), "Receta.pdf") { it.writeText("x") }
        val shared = documents.write(documents.ticket(), "Receta.pdf") { it.writeText("x") }
        documents.purgeViews()
        assertFalse(viewed.exists())
        assertTrue("La otra app lo lee después", shared.exists())
    }
}
