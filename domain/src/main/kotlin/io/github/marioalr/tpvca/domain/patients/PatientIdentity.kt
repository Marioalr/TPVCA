package io.github.marioalr.tpvca.domain.patients

import io.github.marioalr.tpvca.domain.search.TextSearch
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Lo que la ficha deriva de la identidad del paciente (`Models/PatientRecord.cs`): edad y su texto, iniciales, teléfono con
 * formato y si niega alergias o crónicos. Vectores: `contracts/golden/patient-identity.json` (casos `record` y `phone`) y
 * `allergy-check.json` (casos `summary`).
 */
object PatientIdentity {
    /** Desde esta edad se muestran los antecedentes gineco-obstétricos de una mujer. */
    const val GYNECO_OBSTETRIC_MIN_AGE = 10

    /** Meses cumplidos: (año·12 + mes) de hoy menos los del nacimiento, uno menos si no llega al día; nunca negativo. */
    fun totalMonths(birthDate: LocalDate, today: LocalDate): Int {
        var months = (today.year - birthDate.year) * 12 + today.monthValue - birthDate.monthValue
        if (today.dayOfMonth < birthDate.dayOfMonth) months--
        return maxOf(0, months)
    }

    fun calculateAge(birthDate: LocalDate, today: LocalDate): Int = totalMonths(birthDate, today) / 12

    /**
     * Sin fecha de nacimiento, la edad en años ("1 año", "N años"); con ella: menos de un mes en días, menos de 24 meses en
     * meses, antes de los 6 años "N años M meses" y después solo los años.
     */
    fun formatAge(birthDate: LocalDate?, age: Int, today: LocalDate): String {
        if (birthDate == null) return if (age == 1) "1 año" else "${maxOf(0, age)} años"
        val months = totalMonths(birthDate, today)
        if (months < 1) {
            val days = maxOf(0L, ChronoUnit.DAYS.between(birthDate, today))
            return if (days == 1L) "1 día" else "$days días"
        }
        if (months < 24) return if (months == 1) "1 mes" else "$months meses"
        val years = months / 12
        val rest = months % 12
        if (years < 6 && rest > 0) return "$years años $rest ${if (rest == 1) "mes" else "meses"}"
        return "$years años"
    }

    /** Con coma ("Pérez, Juan"), la primera letra de lo de después y de lo de antes; si no, de las dos primeras palabras; "?" sin nombre. */
    fun initials(fullName: String?): String {
        val name = fullName.orEmpty().trim()
        if (name.isEmpty()) return "?"
        val first: String
        val second: String
        if (name.contains(',')) {
            val parts = name.split(',', limit = 2)
            first = parts[1].trim()
            second = parts[0].trim()
        } else {
            val words = name.split(' ').filter { it.isNotEmpty() }
            first = words[0]
            second = words.getOrElse(1) { "" }
        }
        val a = if (first.isNotEmpty()) first[0].uppercaseChar().toString() else ""
        val b = if (second.isNotEmpty()) second[0].uppercaseChar().toString() else ""
        return a + b
    }

    // "ext", "ext.", "extension" o "x" (sin letra antes) o "#", seguidos de un dígito, hasta el final (sobre el texto normalizado)
    private val EXTENSION = Regex("(?U)(?:(?<![a-z])(?:extension|ext\\.?|x)|#)\\s*[0-9].*$", RegexOption.DOT_MATCHES_ALL)

    /** Los dígitos ASCII del teléfono sin su extensión (`DuplicatePatientRules.PhoneDigitsOf`, revisión 20). */
    fun phoneDigitsOf(phone: String?): String =
        EXTENSION.replace(TextSearch.normalize(phone), "").filter { it in '0'..'9' }

    /** Los últimos 10 dígitos: hasta 3 tal cual, hasta 6 "ddd-ddd", más "ddd-ddd-dddd". */
    fun formatPhoneNumber(phone: String?): String {
        if (phone.isNullOrBlank()) return ""
        var digits = phoneDigitsOf(phone)
        if (digits.length > 10) digits = digits.substring(digits.length - 10)
        return when {
            digits.length <= 3 -> digits
            digits.length <= 6 -> "${digits.substring(0, 3)}-${digits.substring(3)}"
            else -> "${digits.substring(0, 3)}-${digits.substring(3, 6)}-${digits.substring(6)}"
        }
    }

    private val DENIED_ALLERGY_ANSWERS = setOf(
        "ninguna", "ninguna conocida", "ningunas", "negadas", "negada", "niega", "niega alergias",
        "no", "sin alergias", "no conocidas", "no conocida", "negativas", "nada",
    )

    /** El texto niega alergias ("Negadas", "Ninguna conocida.", "No"), normalizado y sin el punto final. */
    fun deniesAllergies(text: String?): Boolean {
        val normalized = TextSearch.normalize(text).trimEnd('.')
        return normalized.isNotEmpty() && normalized in DENIED_ALLERGY_ANSWERS
    }

    /** Niega enfermedades crónicas: lo mismo que las alergias, lo que empieza con "ningun", "niega" o "negad", o "sin …". */
    fun deniesChronicDiseases(text: String?): Boolean {
        val normalized = TextSearch.normalize(text).trimEnd('.')
        return normalized.isNotEmpty() &&
            (
                normalized in DENIED_ALLERGY_ANSWERS || normalized.startsWith("ningun") || normalized.startsWith("niega") ||
                    normalized.startsWith("negad") ||
                    normalized in setOf("sin enfermedades", "sin enfermedades cronicas", "sin antecedentes", "sin cronicos")
                )
    }
}
