package io.github.marioalr.tpvca.domain.search

import io.github.marioalr.tpvca.domain.model.Product

/**
 * Qué producto es un renglón de la receta o de los insumos, y qué es medicamento o insumo (`Services/Rules/CatalogMatchingRules.cs`
 * y `PatientSheetRows.FindProductByName`). Vectores: `contracts/golden/catalog-matching.json`.
 */
object CatalogMatchingRules {
    /**
     * El renglón [name] es este producto (sin acentos ni mayúsculas): su nombre, "Nombre (Presentación)", su nombre seguido de
     * un espacio y algo más, su genérico o uno de sus renglones de la receta ([medicineLineMatches]).
     */
    fun productMatchesName(product: Product, name: String): Boolean {
        val n = TextSearch.normalize(name)
        val productName = TextSearch.normalize(product.name)
        return n == productName ||
            n == TextSearch.normalize("${product.name} (${product.presentation.orEmpty()})") ||
            n.startsWith("$productName ") ||
            (!product.genericName.isNullOrEmpty() && n == TextSearch.normalize(product.genericName)) ||
            medicineLineMatches(product, name, normalized = true)
    }

    /**
     * El texto es un renglón de la receta de este producto con genérico: el de hoy con o sin el principio activo, o el de v1.23
     * a v1.45. [normalized]: sin acentos ni mayúsculas; si no, sin distinguir mayúsculas pero con acentos.
     */
    fun medicineLineMatches(product: Product, text: String?, normalized: Boolean): Boolean {
        if (product.genericName.isNullOrEmpty() || text.isNullOrBlank()) return false
        val parts = PrescriptionNaming.parts(product)
        val candidates = listOf(parts.compose(true), parts.compose(false), PrescriptionNaming.legacyMedicineLine(product))
        if (normalized) {
            val n = TextSearch.normalize(text)
            return candidates.any { TextSearch.normalize(it) == n }
        }
        val clean = text.trim()
        return candidates.any { it.equals(clean, ignoreCase = true) }
    }

    /**
     * El producto de un renglón por su nombre: el nombre, "Nombre (Presentación)", el genérico o un renglón de la receta, primero
     * sin distinguir mayúsculas pero con acentos y, si ninguno, sin acentos ni mayúsculas; el primero de la lista. Siempre igual de
     * punta a punta. `null` sin texto o si ninguno.
     */
    fun findProductByName(products: List<Product>, name: String?): Product? {
        if (name.isNullOrBlank()) return null
        val clean = name.trim()
        products.firstOrNull { p ->
            p.name.equals(clean, ignoreCase = true) ||
                "${p.name} (${p.presentation.orEmpty()})".equals(clean, ignoreCase = true) ||
                (!p.genericName.isNullOrEmpty() && p.genericName.equals(clean, ignoreCase = true)) ||
                medicineLineMatches(p, clean, normalized = false)
        }?.let { return it }

        val normalized = TextSearch.normalize(clean)
        return products.firstOrNull { p ->
            TextSearch.normalize(p.name) == normalized ||
                TextSearch.normalize("${p.name} (${p.presentation.orEmpty()})") == normalized ||
                (!p.genericName.isNullOrEmpty() && TextSearch.normalize(p.genericName) == normalized) ||
                medicineLineMatches(p, clean, normalized = true)
        }
    }

    /**
     * Medicamento: categoría que dice "Medicamento" o "Fármaco", o código que empieza con MED; no lo es una categoría de
     * curación, insumo, antiséptico o diagnóstico, ni un código CUR, INS, ANT, DIA o MAT; lo demás sí (sin distinguir
     * mayúsculas; los acentos cuentan).
     */
    fun isProductMedication(p: Product): Boolean {
        val category = p.categoryName
        if (category != null && (category.contains("Medicamento", ignoreCase = true) || category.contains("Fármaco", ignoreCase = true))) {
            return true
        }
        if (p.code.startsWith("MED", ignoreCase = true)) return true
        if (category != null && listOf("Curación", "Insumo", "Antiséptico", "Diagnóstico").any { category.contains(it, ignoreCase = true) }) {
            return false
        }
        if (listOf("CUR", "INS", "ANT", "DIA", "MAT").any { p.code.startsWith(it, ignoreCase = true) }) return false
        return true
    }

    /** Insumo: todo lo que no es medicamento. */
    fun isProductSupply(p: Product): Boolean = !isProductMedication(p)
}
