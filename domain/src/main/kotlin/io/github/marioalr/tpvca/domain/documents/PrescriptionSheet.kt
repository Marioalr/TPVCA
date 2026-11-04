package io.github.marioalr.tpvca.domain.documents

import io.github.marioalr.tpvca.domain.model.ClinicProfile
import io.github.marioalr.tpvca.domain.model.Patient
import io.github.marioalr.tpvca.domain.model.PrescriptionItem
import io.github.marioalr.tpvca.domain.patients.VitalSignsRules
import io.github.marioalr.tpvca.domain.text.NetDecimal
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Un medicamento de la receta: el nombre (en negritas), el detalle (" · presentación") y la posología. */
data class PrescriptionSheetLine(val medicine: String, val posology: String, val detail: String = "")

/** El papel de la receta: media carta horizontal o carta si no cabe. */
enum class PrescriptionPaper(val pageSize: String) {
    HalfLetter("8.5in 5.5in"),
    Letter("8.5in 11in"),
}

/**
 * Lo que dice la receta (`Models/PrescriptionSheet.cs`): la misma hoja de la impresión, el HTML de la PC y el PDF del celular.
 * Vectores: `contracts/golden/prescription-sheet.json`.
 */
data class PrescriptionSheet(
    val doctorName: String,
    val university: String,
    val licenseLine: String,
    val patientName: String,
    val age: String,
    val date: String,
    val allergies: String,
    val hasAllergies: Boolean,
    val bloodPressure: String,
    val temperature: String,
    val weight: String,
    val height: String,
    val oxygenSaturation: String,
    val medicines: List<PrescriptionSheetLine>,
    val generalMeasures: String,
    val phone: String,
    val address: String,
    val paper: PrescriptionPaper = PrescriptionPaper.HalfLetter,
) {
    companion object {
        const val DEFAULT_DOCTOR_NAME = "Dr. Titular"

        /** Las medidas generales de siempre (`PrescriptionDefaults.Indications`). */
        const val DEFAULT_INDICATIONS =
            "Consumir abundantes líquidos, mantener reposo relativo, completar el esquema prescrito y acudir de inmediato a valoración " +
                "médica o urgencias si presenta fiebre persistente, dificultad respiratoria o reacciones adversas."

        /** Lo que guarda la PC cuando el plan solo dijo que siga la receta (`SavedTreatmentPlanPlaceholder`). */
        private const val SAVED_PLAN_PLACEHOLDER = "Tratamiento e indicaciones médicas según receta."

        /** Las medidas generales de una receta anterior (`ConsultationDetailViewModel.Indications`): su plan o, sin plan, las de siempre. */
        fun indicationsOf(treatmentPlan: String?): String =
            if (treatmentPlan.isNullOrBlank() || treatmentPlan.trim() == SAVED_PLAN_PLACEHOLDER) DEFAULT_INDICATIONS else treatmentPlan.trim()

        /** El tamaño de la letra del nombre del médico en la hoja (`LetterheadHtml.DoctorFontPx`): más chica si es muy largo, para que quepa en un renglón. */
        fun doctorFontPx(name: String): Int = kotlin.math.floor(600.0 / (maxOf(1, name.trim().length) * 0.55)).toInt().coerceIn(16, 30)

        private val DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        private val HUNDRED = BigDecimal(100)

        /**
         * La hoja del paciente con sus medicamentos, el día [date] (hoy en el consultorio: la edad y la fecha dependen de él).
         * Los signos son los de la consulta ([vitalSigns]) o, si no hay, los del expediente.
         */
        fun create(
            patient: Patient,
            items: Iterable<PrescriptionItem>?,
            profile: ClinicProfile?,
            generalNotes: String?,
            date: LocalDate,
            vitalSigns: String? = null,
        ): PrescriptionSheet {
            val p = profile ?: ClinicProfile()
            val vitals = VitalSignsRules.parse(vitalSigns ?: patient.vitalSigns)
            val license = p.professionalLicense.orEmpty().trim()
            fun f(value: BigDecimal?, min: Int, max: Int) = value?.let { NetDecimal.custom(it, min, max) }
            val sys = f(vitals.bloodPressureSystolic, 0, 0)
            val dia = f(vitals.bloodPressureDiastolic, 0, 0)
            val height = vitals.height?.takeIf { it.signum() > 0 }
            return PrescriptionSheet(
                doctorName = if (p.doctorName.isNullOrBlank()) DEFAULT_DOCTOR_NAME else p.doctorName.trim(),
                university = p.university.orEmpty().trim(),
                licenseLine = if (license.isNotEmpty()) "Cédula Profesional $license" else "",
                patientName = patient.fullName.trim(),
                age = patient.ageDisplay(date),
                date = date.format(DATE),
                allergies = patient.allergySummary,
                hasAllergies = patient.hasAllergies,
                bloodPressure = if (sys != null && dia != null) "$sys/$dia mmHg" else "",
                temperature = f(vitals.temperature, 1, 1)?.let { "$it °C" } ?: "",
                weight = f(vitals.weight, 0, 2)?.let { "$it kg" } ?: "",
                height = height?.let { "${NetDecimal.custom(it.multiply(HUNDRED), 0, 1)} cm" } ?: "",
                oxygenSaturation = f(vitals.oxygenSaturation, 0, 1)?.let { "$it %" } ?: "",
                medicines = (items ?: emptyList())
                    .filter { it.medicineName.isNotBlank() }
                    .map { PrescriptionSheetLine(it.medicineName.trim(), it.dosage.orEmpty().trim(), it.sheetDetail) },
                generalMeasures = if (generalNotes.isNullOrBlank()) DEFAULT_INDICATIONS else generalNotes.trim(),
                phone = p.phone.orEmpty().trim(),
                address = p.address.orEmpty().trim(),
            )
        }
    }
}

/**
 * Lo que dibuja la receta y en qué lugar (`Services/Documents/PrescriptionHtmlBuilder.cs` y `LetterheadHtml.cs`): el PDF del
 * celular (K4) pone estos valores donde la PC pone cada `data-field`. Vectores: `contracts/golden/prescription-html-fields.json`.
 */
data class PrescriptionLayout(
    val pageSize: String,
    /** Los valores con `data-field`: paciente, edad, fecha, alergias, ta, temp, peso, talla, spo2, telefono, domicilio. */
    val fields: Map<String, String>,
    /** "alergias" en rojo (#991B1B) solo si tiene alergias. */
    val allergiesAlert: Boolean,
    val doctor: String,
    val university: String,
    val license: String,
    /** Cada medicamento: el nombre en negritas seguido del detalle sin negritas, y la posología en el renglón siguiente. */
    val medicines: List<Pair<String, String>>,
    /** Las medidas generales con "\n" entre renglones. */
    val generalMeasures: String,
    /** Los iconos del pie (WhatsApp junto al teléfono y ubicación junto al domicilio), solo si hay texto. */
    val footerIcons: Int,
) {
    companion object {
        /** Márgenes de la hoja del Excel: arriba y abajo 0.197 in, a los lados 0.236 in. */
        const val MARGIN_TOP_BOTTOM_INCHES = 0.197
        const val MARGIN_LEFT_RIGHT_INCHES = 0.236
        const val ALERT_COLOR = "#991B1B"

        fun of(sheet: PrescriptionSheet): PrescriptionLayout = PrescriptionLayout(
            pageSize = sheet.paper.pageSize,
            fields = linkedMapOf(
                "paciente" to sheet.patientName,
                "edad" to sheet.age,
                "fecha" to sheet.date,
                "alergias" to sheet.allergies,
                "ta" to sheet.bloodPressure,
                "temp" to sheet.temperature,
                "peso" to sheet.weight,
                "talla" to sheet.height,
                "spo2" to sheet.oxygenSaturation,
                "telefono" to sheet.phone,
                "domicilio" to sheet.address,
            ),
            allergiesAlert = sheet.hasAllergies,
            doctor = sheet.doctorName,
            university = sheet.university,
            license = sheet.licenseLine,
            medicines = sheet.medicines.map { (it.medicine + it.detail) to it.posology },
            generalMeasures = sheet.generalMeasures.replace("\r\n", "\n"),
            footerIcons = listOf(sheet.phone, sheet.address).count { it.isNotBlank() },
        )
    }
}
