package io.github.marioalr.tpvca.domain.patients

import io.github.marioalr.tpvca.domain.model.Patient
import io.github.marioalr.tpvca.domain.model.Product
import io.github.marioalr.tpvca.domain.search.PrescriptionNaming
import io.github.marioalr.tpvca.domain.search.TextSearch
import java.util.Locale

/**
 * El aviso de alergia al recetar y al cargar «Se surte aquí» (`Services/Rules/AllergyRules.cs`; revisión 20: marcas del
 * catálogo y palabras excluidas de las familias). Vectores: `contracts/golden/allergy-check.json`.
 */
object AllergyRules {
    /** Las familias de fármacos (llave y miembros), en su orden. Los miembros con acento nunca coinciden (se compara sin acentos). */
    val DRUG_FAMILIES: List<Pair<String, List<String>>> = listOf(
        "penicilina" to listOf("penicilina", "amoxicilina", "ampicilina", "dicloxacilina", "bencilpenicilina", "clavulonato", "clavulanico", "clavulánico", "augmentin"),
        "betalactamico" to listOf("penicilina", "amoxicilina", "ampicilina", "cefalexina", "ceftriaxona", "cefotaxima", "cefuroxima"),
        "cefalosporina" to listOf("cefalexina", "ceftriaxona", "cefotaxima", "cefuroxima", "cefixima", "cefazolina"),
        "aines" to listOf(
            "aspirina", "acido acetilsalicilico", "ácido acetilsalicílico", "ibuprofeno", "naproxeno", "ketorolaco", "diclofenaco", "meloxicam",
            "piroxicam", "indometacina", "clonixinato",
        ),
        "aspirina" to listOf("aspirina", "acido acetilsalicilico", "ácido acetilsalicílico", "aas"),
        "ibuprofeno" to listOf("ibuprofeno", "motrin", "advil"),
        "sulfa" to listOf("sulfa", "sulfas", "sulfonamida", "sulfametoxazol", "trimetoprima", "bactrim", "sulfadiazina", "sulfasalazina"),
        "macrolido" to listOf("azitromicina", "claritromicina", "eritromicina"),
        "quinolona" to listOf("ciprofloxacino", "levofloxacino", "moxifloxacino"),
    )

    /** Las palabras que no cuentan para una familia (el sulfato ferroso no es una sulfonamida). */
    val FAMILY_EXCLUDED_WORDS: Map<String, List<String>> = mapOf("sulfa" to listOf("sulfato", "sulfatos"))

    /** Las palabras de sal y concentración que no forman la base de un genérico. */
    val SALT_WORDS: Set<String> = setOf(
        "potasico", "potasica", "sodico", "sodica", "calcico", "calcica", "magnesico", "magnesica", "clorhidrato", "hidrocloruro",
        "bromhidrato", "maleato", "succinato", "besilato", "mesilato", "tartrato", "citrato", "fosfato", "sulfato", "acetato",
        "fumarato", "hiclato", "valerato", "propionato", "dipropionato", "furoato", "monohidrato", "dihidrato", "trihidrato",
        "anhidro", "anhidra", "base", "sal", "de", "del",
        "mg", "g", "mcg", "ug", "ml", "ui", "u",
    )

    const val MIN_GENERIC_BASE_LETTERS = 4

    private val EXCLUDED_PATTERNS: Map<String, List<Regex>> = FAMILY_EXCLUDED_WORDS.mapValues { (_, words) ->
        words.map { Regex("(?<![\\p{L}\\p{Nd}])${Regex.escape(it)}(?![\\p{L}\\p{Nd}])") }
    }

    private val WORD_SPLIT = Regex("[^\\p{L}\\p{Nd}%]+")

    /** El texto normalizado sin las palabras excluidas de la familia (cada una por un espacio). */
    fun withoutExcludedWords(normalizedText: String, familyKey: String): String {
        var text = normalizedText
        EXCLUDED_PATTERNS[familyKey]?.forEach { text = it.replace(text, " ") }
        return text
    }

    fun matchesFamily(normalizedText: String, familyKey: String, members: List<String>): Boolean {
        val text = withoutExcludedWords(normalizedText, familyKey)
        return text.contains(familyKey) || members.any { text.contains(it) }
    }

    /** Los genéricos de los productos del catálogo cuyo nombre o marca es la alergia (sin repetir). */
    fun genericsOfBrand(allergy: String, catalog: Iterable<Product>?): List<String> {
        val result = mutableListOf<String>()
        if (catalog == null) return result
        val normAllergy = TextSearch.normalize(allergy)
        if (normAllergy.isEmpty()) return result
        for (product in catalog) {
            val generic = product.genericName.orEmpty().trim()
            val normGeneric = TextSearch.normalize(generic)
            if (normGeneric.isEmpty() || normGeneric == normAllergy) continue
            val sameName = TextSearch.normalize(product.name) == normAllergy
            val sameBrand = TextSearch.normalize(PrescriptionNaming.brandOf(product.name, product.genericName)) == normAllergy
            if ((sameName || sameBrand) && result.none { TextSearch.normalize(it) == normGeneric }) result.add(generic)
        }
        return result
    }

    /** El genérico normalizado sin la sal ni la concentración ("losartan potasico 50 mg" → "losartan"); `null` si no queda base. */
    fun genericBase(generic: String?): String? {
        val normalized = TextSearch.normalize(generic)
        val words = WORD_SPLIT.split(normalized)
            .filter { w -> w.isNotEmpty() && !w.any { Character.isDigit(it) } && !w.contains('%') && w !in SALT_WORDS }
        val baseText = words.joinToString(" ")
        if (baseText.count { Character.isLetter(it) } < MIN_GENERIC_BASE_LETTERS || baseText == normalized) return null
        return baseText
    }

    private fun containsWords(normalizedText: String, words: String): Boolean {
        val text = " " + WORD_SPLIT.split(normalizedText).filter { it.isNotEmpty() }.joinToString(" ") + " "
        return text.contains(" $words ")
    }

    /** El resultado del aviso: si hay conflicto, con qué alergia (como se escribió) y el texto. */
    data class Check(val hasConflict: Boolean, val matchedAllergy: String, val warningDetails: String) {
        companion object {
            val NONE = Check(false, "", "")
        }
    }

    /**
     * El aviso para [medicineName] (lo escrito en la receta; si es nulo, el nombre de [product]) con el genérico de [product] y
     * el principio activo del renglón ([activeIngredient]). [catalog]: los productos del equipo (para las marcas, paso 1b).
     */
    fun checkMedication(
        patient: Patient?,
        product: Product?,
        medicineName: String? = null,
        activeIngredient: String? = null,
        catalog: Iterable<Product>? = null,
    ): Check {
        if (patient == null || !patient.hasAllergies || patient.allergies.isBlank()) return Check.NONE

        val medName = (medicineName ?: product?.name).orEmpty().trim()
        val generic = product?.genericName.orEmpty().trim()
        var medCombined = "$medName $generic".trim()
        val ingredient = activeIngredient.orEmpty().trim()
        if (ingredient.isNotEmpty() && !TextSearch.normalize(medName).contains(TextSearch.normalize(ingredient))) {
            medCombined = "$medCombined $ingredient".trim()
        }
        if (medCombined.isBlank()) return Check.NONE

        val normalizedMed = TextSearch.normalize(medCombined)
        val allergyTokens = patient.allergies.split(',', ';', '/', '\n', '\r')
            .filter { it.isNotEmpty() }
            .map { it.trim() }
            .filter { it.isNotBlank() && !PatientIdentity.deniesAllergies(it) }

        for (allergy in allergyTokens) {
            val normAllergy = TextSearch.normalize(allergy)

            var medForDirect = normalizedMed
            for ((familyKey, members) in DRUG_FAMILIES) {
                if (matchesFamily(normAllergy, familyKey, members)) medForDirect = withoutExcludedWords(medForDirect, familyKey)
            }

            // 1. Coincidencia directa
            if (medForDirect.contains(normAllergy) ||
                (normAllergy.length >= 4 && medForDirect.trim().isNotEmpty() && normAllergy.contains(medForDirect.trim()))
            ) {
                return Check(true, allergy, "El paciente registra alergia a '$allergy'. El medicamento recetado contiene '$medName'.")
            }

            // 1b. La alergia escrita con la marca avisa con el genérico de ese producto del catálogo
            val generics = genericsOfBrand(allergy, catalog)
            for (brandGeneric in generics) {
                val base = genericBase(brandGeneric)
                if (normalizedMed.contains(TextSearch.normalize(brandGeneric)) || (base != null && containsWords(normalizedMed, base))) {
                    return Check(true, allergy, "El paciente registra alergia a '$allergy' ($brandGeneric). El medicamento recetado contiene '$medName'.")
                }
            }

            // 2. Por familias (la alergia con los genéricos de su marca)
            val allergyForFamilies = if (generics.isEmpty()) normAllergy else TextSearch.normalize("$allergy ${generics.joinToString(" ")}")
            for ((familyKey, members) in DRUG_FAMILIES) {
                if (matchesFamily(allergyForFamilies, familyKey, members) && matchesFamily(normalizedMed, familyKey, members)) {
                    return Check(
                        true, allergy,
                        "Riesgo de hipersensibilidad: El paciente registra alergia a '$allergy' (grupo ${familyKey.uppercase(Locale.ROOT)}). " +
                            "El medicamento recetado '$medName' pertenece o presenta reactividad cruzada con esta familia farmacológica.",
                    )
                }
            }
        }
        return Check.NONE
    }
}
