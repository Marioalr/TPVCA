package io.github.marioalr.tpvca.data.security

import io.github.marioalr.tpvca.sync.link.LabFileRules
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** La caché de resultados, con una llave en memoria en lugar del Keystore. */
class LabFileCacheTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun cache(wrapper: SecretWrapper = MemorySecretWrapper(), max: Long = 1000) = LabFileCache(File(temp.root, "lab"), wrapper, max)

    private fun bytes(seed: Int, size: Int = 400) = ByteArray(size) { (it + seed).toByte() }

    @Test
    fun guardaCifradoYRegresaLoMismo() {
        val cache = cache()
        val content = bytes(1)
        val sha = LabFileRules.sha256(content)
        assertNull(cache.read(sha))
        cache.write(sha, content)
        assertArrayEquals(content, cache.read(sha))
        val saved = File(temp.root, "lab/$sha").readBytes()
        assertFalse("En disco va cifrado", saved.toList().windowed(16).contains(content.take(16)))
    }

    /** El estado de la lista solo mira si el archivo existe: no lo descifra, no lo marca como usado ni borra uno dañado (eso es al abrirlo). */
    @Test
    fun contieneNoLeeNiBorra() {
        val cache = cache()
        val content = bytes(3)
        val sha = LabFileRules.sha256(content)
        assertFalse(cache.contains(sha))
        cache.write(sha, content)
        val file = File(temp.root, "lab/$sha")
        file.writeBytes(ByteArray(40))
        file.setLastModified(1_000_000L)
        assertTrue(cache.contains(sha))
        assertTrue("No se borra", file.exists())
        assertEquals("No se marca como usado", 1_000_000L, file.lastModified())
        val pending = bytes(4)
        val pendingSha = LabFileRules.sha256(pending)
        cache.writePending(pendingSha, pending)
        assertTrue(cache.contains(pendingSha))
    }

    @Test
    fun loDanadoSeBorraYVuelveABajar() {
        val cache = cache()
        val content = bytes(2)
        val sha = LabFileRules.sha256(content)
        cache.write(sha, content)
        File(temp.root, "lab/$sha").writeBytes(ByteArray(40))
        assertNull(cache.read(sha))
        assertFalse(File(temp.root, "lab/$sha").exists())
    }

    @Test
    fun loPendienteDeSubirNoEntraAlTopeYAlSubirsePasaALaCache() {
        val cache = cache(max = 1000)
        val content = bytes(5)
        val sha = LabFileRules.sha256(content)
        cache.writePending(sha, content)
        assertEquals(listOf(sha), cache.pending())
        assertTrue(cache.isPending(sha))
        assertArrayEquals("Se lee igual que uno bajado", content, cache.read(sha))
        assertFalse("En disco va cifrado", File(temp.root, "lab/pendientes/$sha").readBytes().toList().windowed(16).contains(content.take(16)))
        // Llenar la caché no lo tira
        for (i in 10..14) {
            val other = bytes(i)
            cache.write(LabFileRules.sha256(other), other)
        }
        assertArrayEquals(content, cache.read(sha))
        assertEquals(listOf(sha), cache.pending())
        // Subido: a la caché, donde ya sí manda el LRU
        cache.settle(sha)
        assertTrue(cache.pending().isEmpty())
        assertFalse(cache.isPending(sha))
        assertTrue(File(temp.root, "lab/$sha").isFile)
        assertArrayEquals(content, cache.read(sha))
        // Volver a agregarlo lo vuelve pendiente (pudo soltarse como huérfano sin subirse): si ya está en la nube, la subida lo regresa con una lectura
        cache.writePending(sha, content)
        assertEquals(listOf(sha), cache.pending())
        assertFalse(File(temp.root, "lab/$sha").exists())
        assertArrayEquals(content, cache.read(sha))
        cache.settle(sha)
        cache.settle("0".repeat(64))
    }

    @Test
    fun conElTopeSaleLoMenosUsado() {
        val cache = cache(max = 1000)
        val shas = (1..3).map { i ->
            val content = bytes(i)
            val sha = LabFileRules.sha256(content)
            cache.write(sha, content)
            File(temp.root, "lab/$sha").setLastModified(1000L * i)
            sha
        }
        val content = bytes(9)
        val last = LabFileRules.sha256(content)
        cache.write(last, content)
        assertNull("El más viejo se fue", cache.read(shas[0]))
        assertNotNull(cache.read(last))
    }

    @Test
    fun alBorrarseSeVaTodoConSuLlave() {
        val wrapper = MemorySecretWrapper()
        val cache = cache(wrapper)
        val content = bytes(3)
        cache.write(LabFileRules.sha256(content), content)
        cache.destroy()
        assertFalse(File(temp.root, "lab").exists())
        assertEquals(1, wrapper.destroyed)
        assertTrue(temp.root.exists())
    }
}
