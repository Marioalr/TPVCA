package io.github.marioalr.tpvca.domain.search

import io.github.marioalr.tpvca.domain.golden.GoldenFile
import io.github.marioalr.tpvca.domain.golden.bool
import io.github.marioalr.tpvca.domain.golden.ids
import io.github.marioalr.tpvca.domain.golden.intOrNull
import io.github.marioalr.tpvca.domain.golden.items
import io.github.marioalr.tpvca.domain.golden.json
import io.github.marioalr.tpvca.domain.golden.jsonObject
import io.github.marioalr.tpvca.domain.golden.jsonStrings
import io.github.marioalr.tpvca.domain.golden.obj
import io.github.marioalr.tpvca.domain.golden.productsIn
import io.github.marioalr.tpvca.domain.golden.s
import io.github.marioalr.tpvca.domain.golden.str
import io.github.marioalr.tpvca.domain.golden.toProduct
import io.github.marioalr.tpvca.domain.model.Patient
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

/** Búsqueda: rank-patients, rank-products, medicine-search y catalog-matching, cada caso desde su `input`. */
class SearchGoldenTest {
    @Test
    fun rankPatients() = GoldenFile.load("rank-patients").run(checks = null) { case ->
        val patients = case.input.items("patients").map {
            Patient(
                fullName = it.str("fullName"),
                recordNumber = it.str("recordNumber"),
                originalFolio = it.s("originalFolio"),
                phone = it.str("phone"),
                lastDiagnosis = it.str("lastDiagnosis"),
            )
        }
        val ranked = PatientSearchRules.rank(patients, case.input.s("query"), case.input.intOrNull("limit") ?: PatientSearchRules.RESULT_LIMIT)
        jsonStrings(ranked.map { it.recordNumber })
    }

    @Test
    fun rankProducts() = GoldenFile.load("rank-products")
        .run(setOf("quantity-prefix", "pos", "pos-catalog", "service-supplies", "supplies-sheet", "browser-supplies")) { case ->
            val input = case.input
            val products = productsIn(input)
            val text = input.s("text")
            when (input.s("check")) {
                "quantity-prefix" -> CatalogSearchRules.parseQuantityPrefix(text).let { (quantity, query) ->
                    jsonObject("quantity" to json(quantity), "query" to json(query))
                }
                "pos" -> {
                    val (quantity, rest) = CatalogSearchRules.parseQuantityPrefix(text)
                    val limit = input.intOrNull("limit")?.let { if (it < 0) Int.MAX_VALUE else it } ?: TextSearch.PRODUCT_SUGGESTION_LIMIT
                    jsonObject("quantity" to json(quantity), "ids" to ids(TextSearch.rankProducts(products, rest, limit)))
                }
                "pos-catalog" -> ids(CatalogSearchRules.posCatalog(products, input.intOrNull("categoryId"), text))
                "service-supplies" -> ids(CatalogSearchRules.serviceSupplySuggestions(products, text))
                "supplies-sheet" -> ids(CatalogSearchRules.supplySuggestions(products, text))
                else -> ids(CatalogSearchRules.browserSupplies(products, text))
            }
        }

    @Test
    fun medicineSearch() = GoldenFile.load("medicine-search").run(setOf("rank", "prescription", "browser")) { case ->
        val input = case.input
        val products = productsIn(input)
        val text = input.s("text")
        when (input.s("check")) {
            "rank" -> ids(
                MedicineSearchRules.rank(
                    products,
                    text,
                    input.intOrNull("limit") ?: MedicineSearchRules.SUGGESTION_LIMIT,
                    input.bool("includePresentation"),
                ),
            )
            "prescription" -> ids(CatalogSearchRules.prescriptionSuggestions(products, text))
            else -> ids(CatalogSearchRules.browserMedications(products, text))
        }
    }

    @Test
    fun catalogMatching() = GoldenFile.load("catalog-matching")
        .run(setOf("product-matches-name", "find-by-name", "kind", "medicine-line", "medicine-line-parts", "with-active-ingredient")) { case ->
            val input = case.input
            val product = input.obj("product")?.let(::toProduct)
            when (input.s("check")) {
                "product-matches-name" -> JsonPrimitive(CatalogMatchingRules.productMatchesName(product!!, input.str("name")))
                "find-by-name" -> json(CatalogMatchingRules.findProductByName(productsIn(input), input.s("name"))?.id)
                "kind" -> JsonPrimitive(if (CatalogMatchingRules.isProductMedication(product!!)) "medicamento" else "insumo")
                "medicine-line" -> JsonPrimitive(PrescriptionNaming.medicineLine(product!!, input.bool("showActiveIngredient", true)))
                "medicine-line-parts" -> PrescriptionNaming.parts(product!!).let {
                    jsonObject("line" to json(it.line), "activeIngredient" to json(it.activeIngredient))
                }
                else -> JsonPrimitive(
                    PrescriptionNaming.withActiveIngredient(input.s("medicineName"), input.s("activeIngredient"), input.bool("show")),
                )
            }
        }
}
