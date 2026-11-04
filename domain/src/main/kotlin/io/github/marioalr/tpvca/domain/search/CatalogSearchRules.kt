package io.github.marioalr.tpvca.domain.search

import io.github.marioalr.tpvca.domain.model.Patient
import io.github.marioalr.tpvca.domain.model.Product
import io.github.marioalr.tpvca.domain.text.MexicanText
import io.github.marioalr.tpvca.domain.text.NetRegex

/**
 * El buscador de pacientes del inicio (`Services/Rules/PatientSearchRules.cs`). Vectores: `contracts/golden/rank-patients.json`.
 */
object PatientSearchRules {
    /** Cuántos resultados muestra el inicio. */
    const val RESULT_LIMIT = 30

    /**
     * Grupo 0 si el nombre empieza con lo escrito, 1 si una palabra del nombre empieza así, 2 si el nombre o "folio folio-sin-
     * conexión diagnóstico" lo contienen, o si lo escrito tiene 4 dígitos o más y están en el teléfono. Orden: grupo y nombre con
     * `Collator` es-MX; iguales en el orden de la lista. Sin texto, ninguno.
     */
    fun rank(patients: Iterable<Patient>, query: String?, limit: Int = RESULT_LIMIT): List<Patient> {
        val q = TextSearch.normalize(query)
        if (q.isEmpty()) return emptyList()
        val digits = q.filter { Character.isDigit(it) }
        val names = MexicanText.nameOrder()
        return patients
            .mapNotNull { p ->
                val name = TextSearch.normalize(p.fullName)
                val other = TextSearch.normalize("${p.recordNumber} ${p.originalFolio.orEmpty()} ${p.lastDiagnosis}")
                val rank = when {
                    name.startsWith(q) -> 0
                    " $name".contains(" $q") -> 1
                    name.contains(q) || other.contains(q) -> 2
                    digits.length >= 4 && p.phone.filter { Character.isDigit(it) }.contains(digits) -> 2
                    else -> -1
                }
                if (rank >= 0) p to rank else null
            }
            .sortedWith(compareBy<Pair<Patient, Int>> { it.second }.thenComparing({ it.first.fullName }, names))
            .take(limit)
            .map { it.first }
    }
}

/**
 * El orden de los medicamentos al recetar (`Services/Rules/MedicineSearchRules.cs`): primero lo que hay en el consultorio.
 * Vectores: `contracts/golden/medicine-search.json`. El desempate es ordinal (por código de carácter), no con `Collator`.
 */
object MedicineSearchRules {
    /** Cuántas sugerencias muestra la hoja de receta. */
    const val SUGGESTION_LIMIT = 20

    const val NO_MATCH = -1

    /** Hay en el consultorio: activo y con piezas. */
    fun isInStock(product: Product): Boolean = product.isActive && product.currentStock > 0

    /**
     * 0 el código no vacío es igual, 1 el nombre empieza así, 2 el genérico empieza así, 3 una palabra del nombre o del genérico
     * empieza así, 4 el nombre, el genérico o el código (y la presentación, si se pide) lo contienen; [NO_MATCH] si no. Sin texto, 0.
     */
    fun matchTier(product: Product, normalizedQuery: String, includePresentation: Boolean = false): Int {
        val q = normalizedQuery
        if (q.isEmpty()) return 0
        val name = TextSearch.normalize(product.name)
        val generic = TextSearch.normalize(product.genericName)
        val code = TextSearch.normalize(product.code)
        return when {
            code.isNotEmpty() && code == q -> 0
            name.startsWith(q) -> 1
            generic.startsWith(q) -> 2
            " $name".contains(" $q") || " $generic".contains(" $q") -> 3
            name.contains(q) || generic.contains(q) || code.contains(q) -> 4
            includePresentation && TextSearch.normalize(product.presentation).contains(q) -> 4
            else -> NO_MATCH
        }
    }

    /**
     * Los candidatos que coinciden: primero los que hay en existencia, luego el grupo, luego el nombre normalizado, el nombre tal
     * cual y el código (los tres ordinales) y al final el orden de la lista; [limit] como mucho (0 o menos = ninguno).
     */
    fun rank(candidates: Iterable<Product>, query: String?, limit: Int = SUGGESTION_LIMIT, includePresentation: Boolean = false): List<Product> {
        val q = TextSearch.normalize(query)
        data class Ranked(val product: Product, val tier: Int, val index: Int, val normalizedName: String)
        return candidates
            .mapIndexed { index, p -> Ranked(p, matchTier(p, q, includePresentation), index, TextSearch.normalize(p.name)) }
            .filter { it.tier != NO_MATCH }
            .sortedWith(
                compareBy<Ranked> { if (isInStock(it.product)) 0 else 1 }
                    .thenBy { it.tier }
                    .thenBy { it.normalizedName }
                    .thenBy { it.product.name }
                    .thenBy { it.product.code }
                    .thenBy { it.index },
            )
            .take(maxOf(0, limit))
            .map { it.product }
    }
}

/**
 * Las búsquedas del catálogo tal como las arma cada pantalla (`Services/Rules/CatalogSearchRules.cs`). Vectores:
 * `contracts/golden/rank-products.json` y `medicine-search.json`.
 */
object CatalogSearchRules {
    /** Cuántas sugerencias muestran la hoja de insumos y el buscador del CIE-10. */
    const val SHEET_SUGGESTION_LIMIT = 20

    // Revisión 20: solo dígitos ASCII. Como .NET: `.` es todo menos \n (`[^\n]`), `$` el final o antes de un \n final (`(?d)`)
    // y `\s` de Unicode (`(?U)`)
    private val QUANTITY_PREFIX = NetRegex.of("^\\s*([0-9]{1,3})\\s*\\*\\s*([^\\n]*)$", "d")

    /** "3*para" → (3, "para"); sin asterisco, (1, el texto tal cual); "0*para" → (1, "para"). */
    fun parseQuantityPrefix(text: String?): Pair<Int, String> {
        val match = QUANTITY_PREFIX.find(text.orEmpty()) ?: return 1 to text.orEmpty()
        return maxOf(1, match.groupValues[1].toInt()) to match.groupValues[2]
    }

    /** El catálogo del Punto de venta: la categoría elegida (Id > 0), por nombre sin texto o con [TextSearch.rankProducts] sin límite. */
    fun posCatalog(products: Iterable<Product>, categoryId: Int?, query: String?): List<Product> {
        val list = if (categoryId != null && categoryId > 0) products.filter { it.categoryId == categoryId } else products.toList()
        return if (query.isNullOrBlank()) {
            list.sortedWith(compareBy(MexicanText.nameOrder()) { it.name })
        } else {
            TextSearch.rankProducts(list, query, Int.MAX_VALUE)
        }
    }

    /** Insumos de un servicio en la consulta: solo insumos activos, con [TextSearch.rankProducts] (8 como mucho). */
    fun serviceSupplySuggestions(products: Iterable<Product>, query: String?): List<Product> =
        TextSearch.rankProducts(products.filter { it.isActive && CatalogMatchingRules.isProductSupply(it) }, query)

    /** La hoja de insumos: los insumos en el orden del catálogo que contienen lo escrito en nombre, genérico o código (20 como mucho). */
    fun supplySuggestions(products: Iterable<Product>, query: String?): List<Product> {
        val supplies = products.filter(CatalogMatchingRules::isProductSupply)
        if (query.isNullOrBlank()) return supplies.take(SHEET_SUGGESTION_LIMIT)
        val q = TextSearch.normalize(query)
        return supplies.filter { TextSearch.matches(q, it.name, it.genericName, it.code) }.take(SHEET_SUGGESTION_LIMIT)
    }

    /** Las sugerencias de la hoja de receta: los medicamentos con [MedicineSearchRules.rank] (20 como mucho). */
    fun prescriptionSuggestions(products: Iterable<Product>, query: String?): List<Product> =
        MedicineSearchRules.rank(products.filter(CatalogMatchingRules::isProductMedication), query)

    /** El catálogo de medicamentos de la consulta: todos los que coinciden, también por presentación. */
    fun browserMedications(products: Iterable<Product>, query: String?): List<Product> =
        MedicineSearchRules.rank(
            products.filter(CatalogMatchingRules::isProductMedication),
            TextSearch.normalize(query.orEmpty()),
            limit = Int.MAX_VALUE,
            includePresentation = true,
        )

    /** El catálogo de insumos de la consulta: los que contienen lo escrito en nombre, genérico, código o presentación, por nombre. */
    fun browserSupplies(products: Iterable<Product>, query: String?): List<Product> {
        val q = TextSearch.normalize(query.orEmpty())
        var list = products.filter(CatalogMatchingRules::isProductSupply)
        if (q.isNotBlank()) list = list.filter { TextSearch.matches(q, it.name, it.genericName, it.code, it.presentation) }
        return list.sortedWith(compareBy(MexicanText.nameOrder()) { it.name })
    }
}
