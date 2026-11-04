package io.github.marioalr.tpvca.documents

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.marioalr.tpvca.data.security.LabFileCache
import io.github.marioalr.tpvca.data.security.SecretWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

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
        val shared = documents.write("Receta.pdf") { it.writeText("receta") }
        val lab = File(context.noBackupFilesDir, "laboratorio-prueba")
        val cache = LabFileCache(lab, NoWrap)
        cache.write("ab", byteArrayOf(1))
        val keep = File(context.noBackupFilesDir, "seguridad-prueba").apply { mkdirs(); resolve("credencial.bin").writeText("x") }
        val draft = File(context.filesDir, "borrador.txt").apply { writeText("x") }
        assertTrue(shared.exists())

        DeviceFiles(context, cache).erase()

        assertFalse("Lo compartido", shared.exists())
        assertFalse(documents.folder.exists())
        assertFalse("La caché de laboratorio", lab.exists())
        assertTrue("Su llave", NoWrap.destroyed)
        assertFalse(draft.exists())
        assertTrue("La credencial la borra LinkFlow, no esto", keep.resolve("credencial.bin").exists())
    }

    @Test
    fun loCompartidoSeBorraPorAntiguedad() {
        var now = 1_000_000_000_000L
        val documents = SharedDocuments(context) { now }
        val file = documents.write("Ticket.pdf") { it.writeText("t") }
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
        val file = documents.write("Receta.pdf") { it.writeText("x") }
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
    }
}
