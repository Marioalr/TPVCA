package io.github.marioalr.tpvca.domain.business

import io.github.marioalr.tpvca.domain.golden.GoldenFile
import io.github.marioalr.tpvca.domain.golden.bool
import io.github.marioalr.tpvca.domain.golden.items
import io.github.marioalr.tpvca.domain.golden.json
import io.github.marioalr.tpvca.domain.golden.s
import io.github.marioalr.tpvca.domain.golden.str
import io.github.marioalr.tpvca.domain.golden.strings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** El catálogo repetido entre equipos (K9b): `duplicate-catalog.json`, cada caso desde su `input`. */
class CatalogGoldenTest {
    private fun item(e: JsonElement): CatalogDuplicateRules.Item = CatalogDuplicateRules.Item(
        kind = CatalogDuplicateRules.kindOfKey(e.str("kind")) ?: error("tipo desconocido: ${e.str("kind")}"),
        syncId = e.str("SyncId").lowercase(),
        code = e.s("Code"),
        name = e.s("Name"),
        presentation = e.s("Presentation"),
        isActive = e.bool("IsActive", true),
    )

    @Test
    fun duplicateCatalog() = GoldenFile.load("duplicate-catalog").run(setOf("code", "name", "compare", "pairs")) { case ->
        val input = case.input
        when (input.s("check")) {
            "code" -> json(CatalogDuplicateRules.codeKey(item(input.getValue("item"))))
            "name" -> json(CatalogDuplicateRules.nameKey(item(input.getValue("item"))))
            "compare" -> json(CatalogDuplicateRules.compare(item(input.getValue("a")), item(input.getValue("b"))).name)
            else -> JsonArray(
                CatalogDuplicateRules.pairs(input.items("catalog").map(::item), input.strings("involving").filterNotNull().toSet()).map {
                    JsonArray(listOf(JsonPrimitive(CatalogDuplicateRules.kindKey(it.first.kind)), JsonPrimitive(it.first.syncId), JsonPrimitive(it.second.syncId), JsonPrimitive(it.match.name)))
                },
            )
        }
    }

    /** El aviso de arriba de Productos, con las palabras de `SyncArrivals.DuplicateCatalogNotice`. */
    @Test
    fun avisoConLosTresPrimerosYCuantosMas() {
        fun product(id: String, code: String, name: String, presentation: String? = null) =
            CatalogDuplicateRules.Item(CatalogDuplicateRules.Kind.Product, id, code, name, presentation)
        val one = CatalogDuplicateRules.Duplicate(product("a", "MED-010", "Paracetamol"), product("b", "MED-011", "Paracetamol"), CatalogDuplicateRules.Match.Name)
        assertEquals(
            "Posible registro repetido en el catálogo con lo que llegó de otro equipo: producto «Paracetamol» (MED-010) y «Paracetamol» (MED-011), mismo nombre. " +
                "Si son lo mismo, desactive uno (sus ventas y lotes se conservan) o cámbiele el código. TPVC no los une solo.",
            CatalogDuplicateRules.notice(listOf(one)),
        )
        val withPresentation = CatalogDuplicateRules.Duplicate(product("c", "", "Omeprazol", "Caja 14"), product("d", " MED-2 ", "Omeprazol", "caja 14"), CatalogDuplicateRules.Match.Name)
        assertEquals("producto «Omeprazol (Caja 14)» y «Omeprazol (caja 14)» (MED-2), mismo nombre", CatalogDuplicateRules.pairText(withPresentation))
        val notice = CatalogDuplicateRules.notice(List(5) { one })
        assertEquals(true, notice.startsWith("Posibles registros repetidos en el catálogo"))
        assertEquals(true, notice.contains(" y 2 más. "))
        assertEquals("", CatalogDuplicateRules.notice(emptyList()))
        assertEquals("catalogo:producto:a:b", CatalogDuplicateRules.noticeKey(CatalogDuplicateRules.Kind.Product, "B", "a"))
    }
}
