package io.github.marioalr.tpvca.domain.model

import io.github.marioalr.tpvca.domain.patients.PatientIdentity
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * Un expediente (`Patients` del contrato; `Models/PatientRecord.cs`): los campos que leen la ficha, el buscador, la receta y
 * las alertas de alergias. [storedAge] es la columna `Age` (la edad capturada sin fecha de nacimiento). Lo que depende de
 * "hoy" recibe el día del consultorio: nunca se lee el reloj del equipo.
 */
data class Patient(
    val syncId: UUID? = null,
    val recordNumber: String = "",
    val originalFolio: String? = null,
    val fullName: String = "",
    val gender: String = "No especificado",
    val birthDate: LocalDate? = null,
    val storedAge: Int = 0,
    val phone: String = "",
    val email: String = "",
    val address: String = "",
    val allergies: String = "",
    val chronicDiseases: String = "",
    val emergencyContactName: String = "",
    val emergencyContactPhone: String = "",
    val occupation: String = "",
    val religion: String = "",
    val familyHistory: String = "",
    val surgeries: String = "",
    val transfusions: String = "",
    val hospitalizations: String = "",
    val immunizationSchedule: String = "",
    val menarcheAge: String = "",
    val sexualOnsetAge: String = "",
    val periodDuration: String = "",
    val papSmear: String = "",
    val pregnanciesCount: Int = 0,
    val cesareansCount: Int = 0,
    val abortionsCount: Int = 0,
    val isCurrentlyPregnant: Boolean = false,
    val gestationalWeeks: String = "",
    val developmentalMilestones: String = "",
    val diet: String = "",
    val housingConditions: String = "",
    val zoonosis: String = "",
    val lastDiagnosis: String = "",
    val vitalSigns: String = "",
    val bloodType: String = "",
    val sexualPartnersCount: String = "",
    val isPeriodHeavy: String = "",
    val clinicalNotes: String = "",
    /** La receta vigente del expediente (`ActivePrescriptionJson`): «Última receta» de la ficha. */
    val activePrescription: List<PrescriptionItem> = emptyList(),
) {
    /** Con fecha de nacimiento, los años cumplidos hoy; sin ella, la edad capturada. */
    fun age(today: LocalDate): Int = birthDate?.let { PatientIdentity.calculateAge(it, today) } ?: storedAge

    /** "36 años", "11 meses", "3 años 2 meses", "5 días" (`PatientRecord.AgeDisplay`). */
    fun ageDisplay(today: LocalDate): String = PatientIdentity.formatAge(birthDate, age(today), today)

    fun isPediatric(today: LocalDate): Boolean = age(today) < 18

    val isFemale: Boolean get() = gender.isNotBlank() && gender.startsWith("Fem", ignoreCase = true)

    fun showsGynecoObstetric(today: LocalDate): Boolean = isFemale && age(today) >= PatientIdentity.GYNECO_OBSTETRIC_MIN_AGE

    val initials: String get() = PatientIdentity.initials(fullName)

    val formattedPhone: String get() = PatientIdentity.formatPhoneNumber(phone)

    val formattedEmergencyContactPhone: String get() = PatientIdentity.formatPhoneNumber(emergencyContactPhone)

    val hasAllergies: Boolean get() = allergies.isNotBlank() && !PatientIdentity.deniesAllergies(allergies)

    val allergiesNotAsked: Boolean get() = allergies.isBlank()

    /** Las alergias, "No interrogadas" o "Negadas". */
    val allergySummary: String
        get() = when {
            hasAllergies -> allergies.trim()
            allergiesNotAsked -> "No interrogadas"
            else -> "Negadas"
        }

    /** La insignia de la ficha: "ALERGIAS: …", "Alergias sin interrogar" o "Alergias negadas". */
    val allergyBadgeText: String
        get() = when {
            hasAllergies -> "ALERGIAS: ${allergies.trim()}"
            allergiesNotAsked -> "Alergias sin interrogar"
            else -> "Alergias negadas"
        }

    val hasChronicDiseases: Boolean get() = chronicDiseases.isNotBlank() && !PatientIdentity.deniesChronicDiseases(chronicDiseases)

    val chronicBadgeText: String get() = if (hasChronicDiseases) "Crónicos: ${chronicDiseases.trim()}" else ""
}

/**
 * Un renglón de la receta (`ActivePrescriptionJson` y `PrescriptionsJson`): [activeIngredient] y [showActiveIngredient] desde
 * la revisión 16.
 */
data class PrescriptionItem(
    val medicineName: String = "",
    val dosage: String? = "",
    val presentation: String? = null,
    val activeIngredient: String? = null,
    val showActiveIngredient: Boolean = true,
) {
    /** El detalle de la receta: " · " + la presentación, o vacío. */
    val sheetDetail: String get() = if (presentation.isNullOrBlank()) "" else " · " + presentation.trim()
}

/** Un estudio de la orden de laboratorio (`LabOrderStudy`): su nombre y el área del laboratorio que lo procesa ("Otros" en uno escrito a mano). */
data class LabStudy(val name: String, val area: String = "")

/**
 * Una consulta (`PatientConsultations`): la nota, la receta y la solicitud de laboratorio (`LabOrderJson`: [labStudies], [labOrderStudies] con su área y
 * [labIndications]), y los signos en sus columnas si ya se guardaron (decimales; `null` = sin dato).
 */
data class Consultation(
    val date: LocalDateTime = LocalDateTime.MIN,
    val folio: String = "",
    val chiefComplaint: String = "",
    val vitalSigns: String = "",
    val diagnosis: String = "",
    val treatmentPlan: String = "",
    val notes: String = "",
    val systemsReview: String = "",
    val physicalExamination: String = "",
    val prescriptions: List<PrescriptionItem> = emptyList(),
    val labStudies: List<String> = emptyList(),
    val labIndications: String = "",
    val vitals: Vitals = Vitals(),
    val labOrderStudies: List<LabStudy> = labStudies.map { LabStudy(it) },
)

/** Los signos numéricos de una consulta (las columnas de `PatientConsultations`; talla en metros). */
data class Vitals(
    val bloodPressureSystolic: BigDecimal? = null,
    val bloodPressureDiastolic: BigDecimal? = null,
    val heartRate: BigDecimal? = null,
    val respiratoryRate: BigDecimal? = null,
    val temperature: BigDecimal? = null,
    val weight: BigDecimal? = null,
    val height: BigDecimal? = null,
    val bmi: BigDecimal? = null,
    val glucose: BigDecimal? = null,
    val oxygenSaturation: BigDecimal? = null,
)

/** Una cita (`Appointments`). [date] es solo la fecha; [time] el texto tal cual ("HH:mm" desde v1.14). */
data class Appointment(
    val syncId: UUID = EMPTY_SYNC_ID,
    val patientName: String = "",
    val date: LocalDate = LocalDate.MIN,
    val time: String? = "",
    val durationMinutes: Int = 30,
    val reason: String = "",
    val status: String = "Programada",
    val notes: String = "",
    val patientRecordNumber: String = "",
    val patientPhone: String = "",
) {
    val isCancelled: Boolean get() = status == "Cancelada"

    val isAttended: Boolean get() = status == "Atendida"

    /** Pendiente el día [today] o después (ni cancelada ni atendida). */
    fun isUpcomingOn(today: LocalDate): Boolean = !date.isBefore(today) && status != "Cancelada" && status != "Atendida"
}

/** El `Guid.Empty` de .NET: una fila nueva que todavía no tiene SyncId. */
val EMPTY_SYNC_ID: UUID = UUID(0L, 0L)
