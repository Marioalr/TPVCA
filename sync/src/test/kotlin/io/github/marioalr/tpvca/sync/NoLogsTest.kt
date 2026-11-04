package io.github.marioalr.tpvca.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Nada de la app escribe en la bitácora: así el token y la URL no pueden llegar a ella por un mensaje de error. */
class NoLogsTest {
    private val forbidden = Regex("""\b(Log\.[vdiwe]|Timber\.|println\(|printStackTrace\(|HttpLoggingInterceptor)""")

    @Test
    fun elCodigoDeLaAppNoEscribeEnLaBitacora() {
        val root = File(System.getProperty("tpvca.root") ?: error("Falta la propiedad tpvca.root: corra las pruebas con Gradle"))
        val hits = listOf("sync", "data", "domain", "app").flatMap { module ->
            File(root, "$module/src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
                file.readLines().withIndex().filter { forbidden.containsMatchIn(it.value) }.map { "${file.name}:${it.index + 1}" }
            }.toList()
        }
        assertEquals(emptyList<String>(), hits)
    }
}
