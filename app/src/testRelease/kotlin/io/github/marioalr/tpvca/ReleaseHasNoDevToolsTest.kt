package io.github.marioalr.tpvca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** La compilación de publicación no trae la pantalla de desarrollo ni los campos del token. */
class ReleaseHasNoDevToolsTest {
    @Test
    fun laPantallaDeDesarrolloNoExiste() {
        val classes = listOf("io.github.marioalr.tpvca.dev.DevSyncActivity", "io.github.marioalr.tpvca.dev.DevSyncRunner")
        for (name in classes) {
            assertEquals(name, null, runCatching { Class.forName(name) }.getOrNull())
        }
    }

    @Test
    fun buildConfigNoLlevaElToken() {
        val fields = BuildConfig::class.java.fields.map { it.name }
        assertFalse(fields.toString(), fields.any { it.startsWith("TPVC_DEV") })
    }
}
