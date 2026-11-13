package io.github.marioalr.tpvca.domain.consultation

import io.github.marioalr.tpvca.domain.documents.PrescriptionSheet
import io.github.marioalr.tpvca.domain.model.Product
import io.github.marioalr.tpvca.domain.search.PrescriptionNaming
import java.time.Duration
import java.time.LocalDateTime
import java.util.Locale

/**
 * Lo pequeño de la nota de la consulta que usan las pantallas (`ViewModels/Patients/ConsultationViewModel.cs` y `Models/ActiveConsultation.cs`): cómo se escribe la T/A, los chips que
 * se agregan al texto, lo que se guarda si un campo quedó vacío y el reloj. No trae vector: `ConsultationNoteRulesTest`.
 */
object ConsultationNoteRules {
    /** Lo que se guarda como motivo o diagnóstico si quedó vacío. */
    const val DEFAULT_REASON = "Consulta Médica General"

    /** Lo que se guarda como plan si no se escribió ni plan ni indicaciones (`PrescriptionPreviewViewModel.SavedTreatmentPlanPlaceholder`). */
    const val SAVED_TREATMENT_PLAN_PLACEHOLDER = PrescriptionSheet.SAVED_PLAN_PLACEHOLDER

    /** La T/A mientras se teclea: guion o espacio valen "/"; "12080" → "120/80", "1208" → "120/8", "9060" → "90/60", "120" → "120". */
    fun formatTa(input: String?): String {
        if (input.isNullOrBlank()) return ""
        val text = input.trim().replace('-', '/').replace(' ', '/')
        if ('/' in text) {
            val slash = text.indexOf('/')
            val left = threeDigits(text.substring(0, slash))
            val right = threeDigits(text.substring(slash + 1))
            return when {
                left.isEmpty() -> right
                right.isEmpty() -> "$left/"
                else -> "$left/$right"
            }
        }
        val digits = text.filter(Character::isDigit).take(6)
        return when {
            digits.length <= 3 -> digits
            digits.length == 4 ->
                if (digits[0] == '1' || digits[0] == '2') "${digits.substring(0, 3)}/${digits.substring(3)}" else "${digits.substring(0, 2)}/${digits.substring(2)}"
            digits.length == 5 -> "${digits.substring(0, 3)}/${digits.substring(3)}"
            else -> "${digits.substring(0, 3)}/${digits.substring(3, 6)}"
        }
    }

    private fun threeDigits(text: String): String = text.filter(Character::isDigit).take(3)

    /** Un chip del motivo: el texto solo si está vacío; si no ", texto en minúsculas" sin el punto final de lo que había. */
    fun appendSentence(current: String, text: String?): String {
        if (text.isNullOrBlank()) return current
        return if (current.isBlank()) text.trim() else "${current.trimEnd().trimEnd('.')}, ${text.trim().lowercase(Locale.ROOT)}"
    }

    /** Un chip de la exploración o de la revisión por aparatos: en su propio renglón. La PC usa `Environment.NewLine`, "\r\n" en Windows; aquí, "\n". */
    fun appendLine(current: String, text: String?): String {
        if (text.isNullOrBlank()) return current
        return if (current.isBlank()) text.trim() else current.trimEnd() + "\n" + text.trim()
    }

    /** El motivo que se guarda: lo escrito, el de la consulta en curso o [DEFAULT_REASON]. */
    fun chiefComplaintToSave(typed: String?, activeChiefComplaint: String?): String = when {
        !typed.isNullOrBlank() -> typed.trim()
        !activeChiefComplaint.isNullOrBlank() -> activeChiefComplaint
        else -> DEFAULT_REASON
    }

    /** El diagnóstico que se guarda: lo escrito o [DEFAULT_REASON]. */
    fun diagnosisToSave(typed: String?): String = if (typed.isNullOrBlank()) DEFAULT_REASON else typed.trim()

    /** El plan que se guarda: lo escrito, las indicaciones generales o [SAVED_TREATMENT_PLAN_PLACEHOLDER]. */
    fun treatmentPlanToSave(typed: String?, generalIndications: String?): String = when {
        !typed.isNullOrBlank() -> typed.trim()
        !generalIndications.isNullOrBlank() -> generalIndications.trim()
        else -> SAVED_TREATMENT_PLAN_PLACEHOLDER
    }

    /** Los demás textos de la nota: sin espacios a los lados, vacío si no hay. */
    fun textToSave(typed: String?): String = typed?.trim().orEmpty()

    /** El reloj de la consulta (`ActiveConsultation.GetFormattedElapsedTime`): "05:09", o "01:02:03" desde la primera hora; 00:00 si [asOf] es anterior a [startedAt]. */
    fun elapsedText(startedAt: LocalDateTime, asOf: LocalDateTime): String {
        val elapsed = Duration.between(startedAt, asOf)
        val total = if (elapsed.isNegative) 0L else elapsed.seconds
        val minutes = pad(total / 60 % 60)
        val seconds = pad(total % 60)
        return if (total >= 3600) "${pad(total / 3600)}:$minutes:$seconds" else "$minutes:$seconds"
    }

    private fun pad(value: Long): String = value.toString().padStart(2, '0')
}

/**
 * La receta durante la consulta (`ViewModels/Patients/State/ConsultationRecordState.cs`): qué medicamentos de la receta anterior faltan en la de ahora ("Repetir"). No trae vector:
 * `ConsultationNoteRulesTest`.
 */
object ConsultationRecordRules {
    /** Lo que se compara de un renglón de la receta. */
    data class Medicine(val name: String?, val activeIngredient: String? = null)

    /**
     * El mismo medicamento: primero por producto (si [findProduct] resuelve los dos renglones al mismo, lo son, aunque uno diga "Paracetamol 500 mg tabletas (Tempra)" y el otro "Tempra
     * 500 mg tabletas (Paracetamol)"); solo si ninguno de los dos es del catálogo, por el texto, con o sin el principio activo entre paréntesis (sin distinguir mayúsculas).
     */
    fun sameMedicine(a: Medicine, b: Medicine, findProduct: ((String?) -> Product?)?): Boolean {
        val productA = findProduct?.invoke(a.name)
        val productB = findProduct?.invoke(b.name)
        if (productA != null || productB != null) {
            if (productA == null || productB == null) return false
            return productA === productB ||
                (productA.syncId.isNotEmpty() && productA.syncId.equals(productB.syncId, ignoreCase = true)) ||
                (productA.id != 0 && productA.id == productB.id)
        }
        return withoutActiveIngredient(a).equals(withoutActiveIngredient(b), ignoreCase = true)
    }

    private fun withoutActiveIngredient(medicine: Medicine): String = PrescriptionNaming.withActiveIngredient(medicine.name, medicine.activeIngredient, false)

    /** Los medicamentos de la receta anterior que aún no están en la de esta consulta, en su orden. */
    fun <T> pendingPreviousPrescription(previous: List<T>, current: List<T>, medicine: (T) -> Medicine, findProduct: ((String?) -> Product?)?): List<T> =
        previous.filter { p -> current.none { c -> sameMedicine(medicine(c), medicine(p), findProduct) } }
}
