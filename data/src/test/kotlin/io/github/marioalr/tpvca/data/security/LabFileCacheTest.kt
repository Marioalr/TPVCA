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
