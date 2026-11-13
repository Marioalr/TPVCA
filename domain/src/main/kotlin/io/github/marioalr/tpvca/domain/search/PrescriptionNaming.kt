package io.github.marioalr.tpvca.domain.search

import io.github.marioalr.tpvca.domain.model.Product
import io.github.marioalr.tpvca.domain.text.MexicanText
import io.github.marioalr.tpvca.domain.text.NetRegex

/**
 * El renglón de un medicamento en la receta (`Services/PrescriptionNaming.cs`, pedido del médico del 2026-10-21): la marca con
 * su concentración y forma afuera y el principio activo entre paréntesis ("Tempra 500 mg tabletas (Paracetamol)"); un genérico
 * sin paréntesis. De v1.23.0 a v1.45 era al revés ([legacyMedicineLine]): esos renglones se siguen reconociendo.
 * Vectores: `contracts/golden/catalog-matching.json`.
 *
 * Las expresiones van con `NetRegex` para que `\w`, `\d`, `\s` y `\b` sean de Unicode como en .NET (y no cambien entre el JDK 17 de
 * la CI y uno más nuevo).
 */
object PrescriptionNaming {
    private val STRENGTH = NetRegex.of(
        "(?<![\\w/.,])(?<num>\\d+(?:[.,]\\d+)?(?:\\s*/\\s*\\d+(?:[.,]\\d+)?)*)\\s*(?<unit>mcg|µg|ug|mg|g|ui|%)" +
            "(?:\\s*/\\s*(?<per>\\d+(?:[.,]\\d+)?\\s*)?(?<perUnit>ml|g))?(?![\\w])",
        "i",
    )

    private val SLASH = NetRegex.of("\\s*/\\s*")
    private val SPACES = NetRegex.of("\\s+")
    private val PARENTHESES = Regex("\\([^()]*\\)")

    // La forma farmacéutica: lo que se busca en la presentación y en el nombre, y cómo se escribe en la receta
    private val FORMS: List<Pair<Regex, String>> = listOf(
        "\\b(tabletas?|tabs?)\\b" to "tabletas",
        "\\b(c[aá]psulas?|caps?)\\b" to "cápsulas",
        "\\bgrageas?\\b" to "grageas",
        "\\bjarabe\\b" to "jarabe",
        "\\bsuspensi[oó]n\\b" to "suspensión",
        "\\b(soluci[oó]n\\s+inyectable|inyectables?|iny|ampolletas?|ampulas?|amp)\\b" to "solución inyectable",
        "\\bgotas\\b" to "gotas",
        "\\bsoluci[oó]n\\b" to "solución",
        "\\bcrema\\b" to "crema",
        "\\b(ung[uü]ento|pomada)\\b" to "ungüento",
        "\\bgel\\b" to "gel",
        "\\b[oó]vulos?\\b" to "óvulos",
        "\\bsupositorios?\\b" to "supositorios",
        "\\bsobres?\\b" to "sobres",
        "\\bparches?\\b" to "parches",
        "\\b(aerosol|spray|inhalador)\\b" to "aerosol",
    ).map { (pattern, form) -> NetRegex.of(pattern, "i") to form }

    /** Lo de afuera ([line]) y lo de adentro del paréntesis ([activeIngredient], `null` si no lleva). */
    data class MedicineLineParts(val line: String, val activeIngredient: String?) {
        fun compose(showActiveIngredient: Boolean): String =
            if (showActiveIngredient && !activeIngredient.isNullOrEmpty()) "$line ($activeIngredient)" else line
    }

    fun medicineLine(product: Product, showActiveIngredient: Boolean = true): String = parts(product).compose(showActiveIngredient)

    /** La presentación que se surte ("Caja con 20 tabletas"); vacía si el producto no la tiene. */
    fun presentation(product: Product): String = product.presentation.orEmpty().trim()

    /** Las partes del renglón: la marca (o el genérico), su concentración y su forma, y el principio activo de una marca. */
    fun parts(product: Product): MedicineLineParts {
        val name = product.name.trim()
        val generic = product.genericName.orEmpty().trim()
        if (generic.isEmpty()) return MedicineLineParts(name, null)

        val brand = brandOf(name, generic) ?: return MedicineLineParts(genericLine(product, name, generic), null)

        val strength = strength(name) ?: strength(product.presentation) ?: strength(generic)
        val ingredient = withoutStrength(generic)
        val parts = mutableListOf(brand)
        if (strength != null) parts.add(strength)
        val form = form(product.presentation) ?: form(name)
        if (form != null && !TextSearch.normalize(ingredient).contains(TextSearch.normalize(form))) parts.add(form)
        return MedicineLineParts(parts.joinToString(" "), ingredient)
    }

    /** El renglón de v1.23.0 a v1.45: el genérico con su concentración y forma, y la marca entre paréntesis. */
    fun legacyMedicineLine(product: Product): String {
        val name = product.name.trim()
        val generic = product.genericName.orEmpty().trim()
        if (generic.isEmpty()) return name
        val line = genericLine(product, name, generic)
        val brand = brandWords(name, generic)
        return if (brand != null) "$line ($brand)" else line
    }

    /**
     * El renglón guardado con el principio activo entre paréntesis al final, o sin él (la casilla «Mostrar (…)»): nunca lo
     * repite si ya lo dice en otro lugar.
     */
    fun withActiveIngredient(medicineName: String?, activeIngredient: String?, show: Boolean): String {
        var line = medicineName.orEmpty().trim()
        val ingredient = activeIngredient.orEmpty().trim()
        if (ingredient.isEmpty()) return line
        if (show && TextSearch.normalize(line).contains(TextSearch.normalize("($ingredient)"))) return line
        if (endsWithActiveIngredient(line, ingredient)) line = line.substring(0, line.lastIndexOf('(')).trimEnd()
        return if (show) "$line ($ingredient)" else line
    }

    /** El renglón termina con "(principio activo)", sin distinguir acentos ni mayúsculas. */
    fun endsWithActiveIngredient(medicineName: String?, activeIngredient: String?): Boolean {
        val line = medicineName.orEmpty().trim()
        val ingredient = activeIngredient.orEmpty().trim()
        val open = line.lastIndexOf('(')
        return ingredient.isNotEmpty() && open >= 0 && TextSearch.normalize(line.substring(open)) == TextSearch.normalize("($ingredient)")
    }

    private fun genericLine(product: Product, name: String, generic: String): String {
        val parts = mutableListOf(generic)
        if (!generic.any { Character.isDigit(it) }) {
            (strength(name) ?: strength(product.presentation))?.let { parts.add(it) }
        }
        val form = form(product.presentation) ?: form(name)
        if (form != null && !TextSearch.normalize(generic).contains(TextSearch.normalize(form))) parts.add(form)
        return parts.joinToString(" ")
    }

    private fun withoutStrength(generic: String): String {
        val stripped = SPACES.replace(STRENGTH.replaceFirst(generic, " "), " ").trim().trimEnd(',', '-', '/').trim()
        return stripped.ifEmpty { generic }
    }

    /** "500 mg", "875/125 mg", "600 mg/100 ml", "0.9 %"; `null` si el texto no trae concentración. */
    fun strength(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val m = STRENGTH.find(text) ?: return null
        val number = SLASH.replace(m.groups["num"]!!.value, "/")
        val unit = when (val u = m.groups["unit"]!!.value.lowercase(java.util.Locale.ROOT)) {
            "ui" -> "UI"
            "ug", "µg" -> "mcg"
            else -> u
        }
        var result = if (unit == "%") "$number %" else "$number $unit"
        val perUnit = m.groups["perUnit"]
        if (perUnit != null) {
            val per = m.groups["per"]?.value.orEmpty().trim()
            result += "/" + (if (per.isNotEmpty()) "$per " else "") + perUnit.value.lowercase(java.util.Locale.ROOT)
        }
        return result
    }

    /** La forma farmacéutica que dice el texto ("tabletas", "jarabe"…); `null` si no la dice. */
    fun form(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return FORMS.firstOrNull { (regex, _) -> regex.containsMatchIn(text) }?.second
    }

    /**
     * La marca: las palabras del nombre antes de la concentración, sin lo que va entre paréntesis, si no empiezan como el
     * genérico. En mayúsculas se escribe en tipo título ("VALCLAN" → "Valclan").
     */
    fun brandOf(name: String?, generic: String?): String? =
        brandWords(if (name.isNullOrBlank()) name else PARENTHESES.replace(name, " "), generic)

    private fun brandWords(name: String?, generic: String?): String? {
        if (name.isNullOrBlank()) return null
        val words = name.trim().split(' ').filter { it.isNotEmpty() }
        val brandWords = words
            .takeWhile { w -> !w.any { Character.isDigit(it) } && !w.startsWith("C/", ignoreCase = true) }
            .toMutableList()
        while (brandWords.isNotEmpty() && form(brandWords.last()) != null) brandWords.removeAt(brandWords.size - 1)
        if (brandWords.isEmpty()) return null

        val firstGeneric = firstWord(generic)
        if (firstGeneric.isNotEmpty() && firstWord(brandWords[0]) == firstGeneric) return null

        val brand = brandWords.joinToString(" ")
        return if (brand == brand.uppercase(java.util.Locale.ROOT)) MexicanText.titleCase(brand.lowercase(java.util.Locale.ROOT)) else brand
    }

    private fun firstWord(text: String?): String =
        TextSearch.normalize(text).split(' ', '/', '-', ',').firstOrNull { it.isNotEmpty() } ?: ""
}
