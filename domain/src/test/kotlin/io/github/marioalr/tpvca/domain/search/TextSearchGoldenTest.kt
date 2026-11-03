package io.github.marioalr.tpvca.domain.search

import io.github.marioalr.tpvca.domain.golden.GoldenFile
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** `TextSearch` contra `contracts/golden/text-search.json`: los mismos casos que pasa la PC. */
class TextSearchGoldenTest {
    private val golden = GoldenFile.load("text-search")

    @Test
    fun elArchivoEsElDeLaRegla() {
        assertEquals("text-search", golden.rule)
        assertEquals("es-MX", golden.culture)
        // Cada caso es de un tipo que la regla conoce: uno nuevo en la PC hace fallar esta prueba
        assertEquals(setOf("normalize", "matches", "best-match"), golden.cases.map { it.requireString("check") }.toSet())
    }

    @Test
    fun pasaTodosLosCasos() = golden.run { case ->
        when (val check = case.requireString("check")) {
            "normalize" -> JsonPrimitive(TextSearch.normalize(case.string("text")))
            // La PC normaliza la consulta antes de llamar a Matches
            "matches" -> JsonPrimitive(
                TextSearch.matches(TextSearch.normalize(case.string("query")), *case.strings("fields").toTypedArray()),
            )
            "best-match" -> JsonPrimitive(TextSearch.findBestMatchIndex(case.strings("options"), case.string("typed")))
            else -> error("Tipo de caso desconocido: $check")
        }
    }
}
