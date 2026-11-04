package io.github.marioalr.tpvca.domain.patients

import io.github.marioalr.tpvca.domain.text.NetRegex
import java.time.DateTimeException
import java.time.LocalDate

/** Lo que se captura en el alta y la edición del paciente y revisa [PatientFormRules.validate]. */
data class PatientFormInput(
    val firstName: String?,
    val lastName: String?,
    val maternalLastName: String?,
    val recordNumber: String?,
    val birthDateText: String?,
    val birthDate: LocalDate?,
    val ageText: String?,
    val gender: String?,
    val allergies: String?,
    val email: String?,
)

/** El nombre partido: nombre(s), paterno y materno. */
data class ParsedName(val firstName: String, val lastName: String, val maternalLastName: String)

/** Lo que revisó [PatientFormRules.validate]: la primera razón para no guardar, o `null`, y la edad. */
data class FormCheck(val problem: String?, val age: Int)

/**
 * Las reglas del alta y la edición del paciente (`Services/Rules/PatientFormRules.cs`). Vectores: `golden/patient-identity.json`, casos `parse-name`, `compose-name`, `birth-date`, `email` y `validate`.
 */
object PatientFormRules {
    private val EMAIL = NetRegex.of("^[^@\\s]+@[^@\\s]+\\.[a-zA-Z]{2,}$", "i")

    /** Los formatos exactos de la PC, con los grupos día, mes y año. `dd`/`MM` piden dos dígitos, `d`/`M` uno o dos; el año, cuatro o dos. */
    private class DateFormat(pattern: String, val day: Int, val month: Int, val year: Int) {
        val regex = Regex(pattern)
    }

    private val BIRTH_DATE_FORMATS = listOf(
        DateFormat("^([0-9]{1,2})/([0-9]{1,2})/([0-9]{4})$", 1, 2, 3),
        DateFormat("^([0-9]{1,2})-([0-9]{1,2})-([0-9]{4})$", 1, 2, 3),
        DateFormat("^([0-9]{1,2})\\.([0-9]{1,2})\\.([0-9]{4})$", 1, 2, 3),
        DateFormat("^([0-9]{4})-([0-9]{2})-([0-9]{2})$", 3, 2, 1),
        DateFormat("^([0-9]{1,2})/([0-9]{1,2})/([0-9]{2})$", 1, 2, 3),
        DateFormat("^([0-9]{2})([0-9]{2})([0-9]{4})$", 1, 2, 3),
    )

    /** Fecha válida (no futura, máximo 130 años) o `null`. Un año de dos dígitos sigue el calendario de .NET: hasta el 49 es 20xx, del 50 en adelante 19xx. */
    fun tryParseBirthDate(text: String?, today: LocalDate): LocalDate? {
        val clean = text?.trim().orEmpty()
        if (clean.isEmpty()) return null
        val date = BIRTH_DATE_FORMATS.firstNotNullOfOrNull { format ->
            val match = format.regex.find(clean) ?: return@firstNotNullOfOrNull null
            val rawYear = match.groupValues[format.year]
            val year = rawYear.toInt().let { if (rawYear.length == 2) (if (it <= 49) 2000 + it else 1900 + it) else it }
            try {
                LocalDate.of(year, match.groupValues[format.month].toInt(), match.groupValues[format.day].toInt())
            } catch (_: DateTimeException) {
                null
            }
        } ?: return null
        if (date.isAfter(today) || date.year < today.year - 130) return null
        return date
    }

    /** Vacío vale (el correo no es obligatorio); si no, algo@dominio.tld. */
    fun isValidEmail(email: String?): Boolean {
        if (email.isNullOrBlank()) return true
        return EMAIL.containsMatchIn(email.trim())
    }

    /** "Apellidos, Nombre(s)" o "Nombre(s) Paterno Materno": con más de tres palabras, las dos últimas son los apellidos. */
    fun parseFullName(fullName: String?): ParsedName {
        if (fullName.isNullOrBlank()) return ParsedName("", "", "")
        val trimmed = fullName.trim()

        // Con coma y algo a cada lado; ",Juan" tiene una sola parte y va por palabras (`Split` de .NET sin las partes vacías)
        val comma = commaParts(trimmed)
        if (comma != null) {
            val surnames = comma.first.trim().split(' ').filter { it.isNotEmpty() }
            return ParsedName(comma.second.trim(), surnames.firstOrNull().orEmpty(), surnames.drop(1).joinToString(" "))
        }

        val tokens = trimmed.split(' ').filter { it.isNotEmpty() }
        return when (tokens.size) {
            1 -> ParsedName(tokens[0], "", "")
            2 -> ParsedName(tokens[0], tokens[1], "")
            3 -> ParsedName(tokens[0], tokens[1], tokens[2])
            else -> ParsedName(tokens.dropLast(2).joinToString(" "), tokens[tokens.size - 2], tokens.last())
        }
    }

    /** `Split(',', 2, RemoveEmptyEntries)`: la primera parte que no está vacía y lo que sigue, sin las comas de en medio. `null` si no hay dos partes. */
    private fun commaParts(text: String): Pair<String, String>? {
        val start = text.indexOfFirst { it != ',' }
        if (start < 0) return null
        val end = text.indexOf(',', start)
        if (end < 0) return null
        val rest = text.substring(end).trimStart(',')
        return if (rest.isEmpty()) null else text.substring(start, end) to rest
    }

    /** "Nombre Paterno Materno" (sin el materno si no hay). */
    fun composeFullName(firstName: String, lastName: String, maternalLastName: String?): String =
        if (maternalLastName.isNullOrBlank()) "${firstName.trim()} ${lastName.trim()}" else "${firstName.trim()} ${lastName.trim()} ${maternalLastName.trim()}"

    /**
     * La primera razón para no guardar, en el orden en que se revisa, o `null`. Con la fecha de nacimiento la edad se calcula; sin ella, se toma la capturada. El folio repetido se revisa aparte
     * (necesita la base).
     */
    fun validate(input: PatientFormInput, today: LocalDate): FormCheck {
        if (input.firstName.isNullOrBlank()) return FormCheck("El nombre(s) del paciente es obligatorio.", 0)
        if (input.lastName.isNullOrBlank()) return FormCheck("El apellido paterno del paciente es obligatorio.", 0)
        if (input.recordNumber.isNullOrBlank()) return FormCheck("El folio del expediente es obligatorio.", 0)
        if (!input.birthDateText.isNullOrBlank() && input.birthDate == null) {
            return FormCheck("La fecha de nacimiento no es válida: use día/mes/año (por ejemplo 14/03/1992) y que no sea futura.", 0)
        }
        val age: Int
        if (input.birthDate != null) {
            age = PatientIdentity.calculateAge(input.birthDate, today)
        } else {
            val typed = parseInt(input.ageText)
            if (typed == null || typed < 0 || typed > 130) return FormCheck("Capture la fecha de nacimiento (o la edad en años si no se conoce).", 0)
            age = typed
        }
        if (input.gender.isNullOrBlank()) return FormCheck("Seleccione el sexo del paciente.", age)
        if (input.allergies.isNullOrBlank()) {
            return FormCheck("Indique las alergias del paciente o escriba \"Negadas\" (no es lo mismo que no haber preguntado).", age)
        }
        if (!isValidEmail(input.email)) return FormCheck("El correo electrónico no tiene un formato válido (ejemplo: usuario@correo.com).", age)
        return FormCheck(null, age)
    }

    /** `int.TryParse`: espacios a los lados y un signo. */
    private fun parseInt(text: String?): Int? = text?.trim()?.takeIf { Regex("^[+-]?[0-9]+$").matches(it) }?.toIntOrNull()
}
