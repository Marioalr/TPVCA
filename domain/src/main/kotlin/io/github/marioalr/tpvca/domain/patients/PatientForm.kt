package io.github.marioalr.tpvca.domain.patients

import io.github.marioalr.tpvca.domain.text.MexicanText
import java.time.LocalDate

/** Una sección de la barra del alta: número, título, estado y si está completa (`PatientFormSection`). */
data class PatientFormSection(
    val key: String,
    val number: String,
    val title: String,
    val status: String,
    val isComplete: Boolean,
    val isRequired: Boolean,
    val isOpen: Boolean,
)

/**
 * El alta o la edición de un expediente mientras se captura (`PatientFormViewModel`): solo textos, como en el formulario, y lo que se deriva de ellos. No cambia: cada tecla es una copia. No sabe de la base ni de Android:
 * la tablet lo guarda como borrador cifrado y, con los campos de [PatientFormFields], lo escribe.
 */
data class PatientForm(
    val recordNumber: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val maternalLastName: String = "",
    val birthDateText: String = "",
    val ageText: String = "",
    val gender: String = "",
    val phone: String = "",
    val email: String = "",
    val address: String = "",
    val bloodType: String = BLOOD_TYPE_UNKNOWN,
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
    val sexualPartnersCount: String = "",
    val periodDuration: String = "",
    val isPeriodHeavy: String = "Normal",
    val papSmear: String = "",
    val pregnanciesCount: String = "0",
    val cesareansCount: String = "0",
    val abortionsCount: String = "0",
    val isCurrentlyPregnant: Boolean = false,
    val gestationalWeeks: String = "",
    val developmentalMilestones: String = "",
    val diet: String = "",
    val housingConditions: String = "",
    val zoonosis: String = "",
    val clinicalNotes: String = "",
    val openSection: String = "Identificacion",
) {
    fun birthDate(today: LocalDate): LocalDate? = PatientFormRules.tryParseBirthDate(birthDateText, today)

    fun hasBirthDate(today: LocalDate): Boolean = birthDate(today) != null

    /** Con fecha, la edad que da; sin ella, la capturada. */
    fun age(today: LocalDate): Int = birthDate(today)?.let { PatientIdentity.calculateAge(it, today) } ?: (ageText.toIntOrNull() ?: 0)

    fun hasAge(today: LocalDate): Boolean = hasBirthDate(today) || ageText.isNotBlank()

    /** "34 años", "2 años 6 meses", "8 meses"… junto a la fecha. */
    fun ageDisplay(today: LocalDate): String {
        val birth = birthDate(today)
        return when {
            birth != null -> PatientIdentity.formatAge(birth, 0, today)
            hasAge(today) -> PatientIdentity.formatAge(null, age(today), today)
            else -> ""
        }
    }

    val isFemale: Boolean get() = gender.isNotBlank() && gender.startsWith("Fem", ignoreCase = true)

    fun isPediatric(today: LocalDate): Boolean = hasAge(today) && age(today) < 18

    /** Antecedentes gineco-obstétricos: mujeres de 10 años o más. */
    fun showsGyneco(today: LocalDate): Boolean = isFemale && hasAge(today) && age(today) >= PatientIdentity.GYNECO_OBSTETRIC_MIN_AGE

    /** Escribir la fecha: con una fecha válida, la edad se calcula sola y su campo queda de solo lectura. */
    fun withBirthDateText(text: String, today: LocalDate): PatientForm {
        val next = copy(birthDateText = text)
        val birth = next.birthDate(today) ?: return next
        return next.copy(ageText = PatientIdentity.calculateAge(birth, today).toString())
    }

    fun withAgeText(text: String): PatientForm = copy(ageText = text.filter { it.isDigit() })

    /** A cada tecla, como la PC: escribir de corrido un número con lada termina en sus últimos 10. */
    fun withPhone(text: String): PatientForm = copy(phone = PatientIdentity.formatPhoneNumber(text))

    fun withEmergencyPhone(text: String): PatientForm = copy(emergencyContactPhone = PatientIdentity.formatPhoneNumber(text))

    fun sections(today: LocalDate): List<PatientFormSection> {
        val list = ArrayList<PatientFormSection>()
        var number = 1
        fun add(key: String, title: String, required: Boolean, complete: Boolean, applicable: Boolean = true) {
            if (!applicable) return
            val status = if (required) (if (complete) "Completa" else "Obligatoria") else (if (complete) "Con datos" else "Opcional")
            list += PatientFormSection(key, (number++).toString(), title, status, complete, required, openSection == key)
        }
        fun any(vararg values: String) = values.any { it.isNotBlank() }
        add("Identificacion", "Identificación", true, any(firstName) && any(lastName) && hasAge(today) && any(gender))
        add("Alergias", "Alergias y crónicos", true, any(allergies))
        add("Contacto", "Contacto y emergencia", false, any(phone, email, address, emergencyContactName))
        add("Heredofamiliares", "Heredofamiliares", false, any(familyHistory))
        add("Patologicos", "Personales patológicos", false, any(surgeries, hospitalizations, transfusions, immunizationSchedule))
        add("Gineco", "Gineco-obstétricos", false, any(menarcheAge, sexualOnsetAge, periodDuration, papSmear) || (pregnanciesCount.toIntOrNull() ?: 0) > 0, showsGyneco(today))
        add("Desarrollo", "Desarrollo", false, any(developmentalMilestones), isPediatric(today))
        add("NoPatologicos", "No patológicos", false, any(diet, housingConditions, zoonosis, occupation))
        add("Notas", "Notas de ingreso", false, any(clinicalNotes))
        return list
    }

    fun progressText(today: LocalDate): String {
        val all = sections(today)
        val missing = all.count { it.isRequired && !it.isComplete }
        return if (missing == 0) "${all.count { it.isComplete }} de ${all.size} secciones · lo obligatorio está completo" else "Faltan $missing sección(es) obligatoria(s)"
    }

    fun pageTitle(isNew: Boolean): String {
        if (isNew) return "Nuevo paciente"
        val name = listOf(firstName, lastName, maternalLastName).filter { it.isNotBlank() }.joinToString(" ")
        return if (name.isBlank()) "Editar expediente" else "Editar: $name"
    }

    fun fullName(): String = PatientFormRules.composeFullName(firstName, lastName, maternalLastName)

    fun check(today: LocalDate): FormCheck = PatientFormRules.validate(
        PatientFormInput(firstName, lastName, maternalLastName, recordNumber, birthDateText, birthDate(today), ageText, gender, allergies, email),
        today,
    )

    /** Lo que se compara para saber si hay algo que recuperar: cambiar de sección no es un cambio. */
    fun sameDataAs(other: PatientForm): Boolean = copy(openSection = "") == other.copy(openSection = "")

    companion object {
        const val BLOOD_TYPE_UNKNOWN = "No determinado"
        val GENDER_OPTIONS = listOf("Masculino", "Femenino", "Otro / No especificado")
        val BLOOD_TYPE_OPTIONS = listOf(BLOOD_TYPE_UNKNOWN, "O+", "O-", "A+", "A-", "B+", "B-", "AB+", "AB-")
        val PERIOD_OPTIONS = listOf("Normal", "Abundante", "Escaso")

        /** Un error de validación abre la sección donde está el dato que falta. */
        fun sectionForError(message: String): String {
            val text = message.lowercase(java.util.Locale.ROOT)
            return when {
                "alergia" in text -> "Alergias"
                "correo" in text -> "Contacto"
                else -> "Identificacion"
            }
        }

        /**
         * «Crear paciente "…"» desde la búsqueda (`CreatePatientFromSearchAsync`): si se escribió un nombre, no un folio o un teléfono, el alta empieza con él.
         */
        fun fromSearch(typed: String, recordNumber: String): PatientForm {
            val base = PatientForm(recordNumber = recordNumber)
            val text = typed.trim()
            if (text.isEmpty() || text.any { it.isDigit() }) return base
            val name = PatientFormRules.parseFullName(text)
            return base.copy(firstName = titled(name.firstName), lastName = titled(name.lastName), maternalLastName = titled(name.maternalLastName))
        }

        private fun titled(text: String) = if (text.isBlank()) "" else MexicanText.titleCase(text.trim().lowercase(io.github.marioalr.tpvca.domain.Clinic.LOCALE))
    }
}

/** Lo que se muestra de un expediente en el aviso de posible duplicado. */
data class DuplicateMatch(val fullName: String, val recordNumber: String, val reason: DuplicateReason)

/** El texto del aviso de posible duplicado (`DuplicateQuestion`). */
object DuplicateQuestion {
    fun describe(reason: DuplicateReason): String = when (reason) {
        DuplicateReason.BirthDateAndPhone -> "misma fecha de nacimiento y mismo teléfono"
        DuplicateReason.BirthDate -> "misma fecha de nacimiento"
        DuplicateReason.Phone -> "mismo teléfono"
        DuplicateReason.None -> ""
    }

    fun text(similar: List<DuplicateMatch>): String {
        val lines = similar.take(5).joinToString("\n") { match ->
            "• " + listOf(match.fullName, match.recordNumber, describe(match.reason)).filter { it.isNotBlank() }.joinToString(" · ")
        }
        return (if (similar.size == 1) "Ya hay un expediente parecido:" else "Ya hay expedientes parecidos:") + "\n\n" + lines +
            (if (similar.size > 5) "\n• y ${similar.size - 5} más" else "") +
            "\n\nSi es el mismo paciente, conteste No y búsquelo en Pacientes. ¿Crear el expediente nuevo de todos modos?"
    }
}
