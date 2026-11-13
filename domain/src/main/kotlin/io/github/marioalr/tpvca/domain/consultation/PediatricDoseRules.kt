package io.github.marioalr.tpvca.domain.consultation

import io.github.marioalr.tpvca.domain.model.Consultation
import io.github.marioalr.tpvca.domain.patients.VitalSignsRules
import io.github.marioalr.tpvca.domain.text.NetDecimal

/**
 * Una regla de la hoja DOSIS PEDIA del Excel (`Models/PediatricDoseRule`). Las cuentas son `Math.Round` de `double` de .NET: al par, después de multiplicar en `double`
 * (5.25 → 5.2 y 37.5 → 38), así que se usa `Math.rint`.
 */
data class PediatricDoseRule(
    val medication: String = "",
    val doseText: String = "",
    val mlPerKg: Double? = null,
    val dropsPerMl: Int? = null,
) {
    val canCalculate: Boolean get() = mlPerKg != null && mlPerKg > 0

    val displayName: String get() = if (doseText.isBlank()) medication else "$medication • $doseText"

    fun calculateMl(weightKg: Double): Double? =
        if (canCalculate && weightKg > 0) Math.rint(mlPerKg!! * weightKg * 10.0) / 10.0 else null

    fun calculateDrops(weightKg: Double): Double? {
        val ml = calculateMl(weightKg) ?: return null
        return if (dropsPerMl != null && dropsPerMl > 0) Math.rint(ml * dropsPerMl) else null
    }
}

/**
 * La calculadora de dosis pediátrica de la receta (`Services/Rules/PediatricDoseRules.cs`): el peso que se sugiere, el resultado que se muestra y la indicación que se copia.
 * Vectores: `contracts/golden/pediatric-dose.json`.
 */
object PediatricDoseRules {
    const val NO_FORMULA_TEXT = "Esta regla no tiene fórmula de ml por kg en el Excel."
    const val ASK_WEIGHT_TEXT = "Capture el peso del paciente (kg)."

    /** Lo que se sugiere como peso solo si es menor que esto (un adulto no usa la calculadora). */
    const val MAX_SUGGESTED_CURRENT_WEIGHT = 70.0

    /** Un número con punto o coma decimal, sin espacios a los lados; `null` si no se lee. */
    fun parseNumber(text: String?): Double? = VitalSignsRules.parseDouble(text)

    /**
     * El peso con que se llena la calculadora al elegir una regla con el peso vacío: el de la consulta en curso si es mayor que 0 y menor que 70; si no, el último peso mayor que
     * 0 de las consultas del paciente (por fecha); vacío si no hay. Con "0.#".
     */
    fun suggestWeight(hasActiveConsultation: Boolean, currentWeightText: String?, consultations: Iterable<Consultation>?): String {
        if (hasActiveConsultation) {
            val current = parseNumber(currentWeightText)
            if (current != null && current > 0 && current < MAX_SUGGESTED_CURRENT_WEIGHT) return NetDecimal.customDouble(current, 0, 1)
        }
        val last = consultations
            ?.filter { it.vitals.weight.let { w -> w != null && w.signum() > 0 } }
            ?.maxByOrNull { it.date }
        return last?.vitals?.weight?.let { NetDecimal.custom(it, 0, 1) }.orEmpty()
    }

    /** Lo que muestra la calculadora: vacío sin regla, el aviso de una regla sin fórmula, el de capturar el peso, o "7.5 ml (150 gotas) por dosis" / "7.5 ml por dosis". */
    fun resultText(rule: PediatricDoseRule?, weightText: String?): String {
        if (rule == null) return ""
        if (!rule.canCalculate) return NO_FORMULA_TEXT
        val weight = parseNumber(weightText)
        if (weight == null || weight <= 0) return ASK_WEIGHT_TEXT
        return "${amount(rule, weight)} por dosis"
    }

    /** La indicación que "Aplicar" copia al medicamento; `null` sin regla, si el peso no es un número o si la regla no da ml con ese peso. */
    fun dosageText(rule: PediatricDoseRule?, weightText: String?): String? {
        if (rule == null) return null
        val weight = parseNumber(weightText) ?: return null
        if (rule.calculateMl(weight) == null) return null
        return "Dar ${amount(rule, weight)} vía oral cada ___ hrs por ___ días"
    }

    private fun amount(rule: PediatricDoseRule, weight: Double): String {
        val ml = NetDecimal.customDouble(rule.calculateMl(weight)!!, 0, 1)
        val drops = rule.calculateDrops(weight)
        return if (drops != null) "$ml ml (${NetDecimal.customDouble(drops, 0, 0)} gotas)" else "$ml ml"
    }
}
