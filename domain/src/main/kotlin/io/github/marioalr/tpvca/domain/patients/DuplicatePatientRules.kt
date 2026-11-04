package io.github.marioalr.tpvca.domain.patients

import io.github.marioalr.tpvca.domain.search.TextSearch
import java.util.Locale

/** Por qué dos expedientes pueden ser el mismo paciente. */
enum class DuplicateReason { None, BirthDate, Phone, BirthDateAndPhone }

/** Lo que se compara de un expediente. [birthDate] es la fecha, sin hora. */
data class DuplicateCandidate(
    val syncId: String,
    val fullName: String?,
    val birthDate: String?,
    val phone: String?,
    val isActive: Boolean,
    val mergedIntoSyncId: String?,
)

/** Un par de posibles duplicados, con el `SyncId` menor primero. */
data class DuplicatePair(val first: String, val second: String, val reason: DuplicateReason)

/**
 * Posibles expedientes duplicados (`Services/Rules/DuplicatePatientRules.cs`, paso 8.23, `golden/duplicate-patients.json`): el nombre normalizado igual y además la misma fecha de nacimiento o los
 * mismos últimos 10 dígitos del teléfono. Solo avisa: nunca une, borra ni renombra nada.
 */
object DuplicatePatientRules {
    /** Sin lookbehind de longitud variable y con los dígitos ASCII escritos a mano: `\d` de ICU también toma los de ancho completo. */
    private val EXTENSION = Regex("(?:(?<![a-z])(?:extension|ext\\.?|x)|#)\\s*[0-9].*$", RegexOption.DOT_MATCHES_ALL)

    /** Como la búsqueda, con los espacios seguidos como uno: todo lo que `Char.isWhitespace` toma por espacio, también el espacio duro. */
    fun normalizedName(name: String?): String {
        val normalized = TextSearch.normalize(name)
        val out = StringBuilder(normalized.length)
        var inSpace = false
        for (c in normalized) {
            if (c.isWhitespace() || c == ' ') {
                if (!inSpace && out.isNotEmpty()) out.append(' ')
                inSpace = true
            } else {
                out.append(c)
                inSpace = false
            }
        }
        return out.toString().trimEnd(' ')
    }

    /** Sin la extensión, solo los dígitos ASCII, los últimos 10. Con menos, `null`: no se compara. */
    fun normalizedPhone(phone: String?): String? {
        if (phone == null) return null
        val text = TextSearch.normalize(phone).replace(EXTENSION, "")
        val digits = text.filter { it in '0'..'9' }
        return if (digits.length < 10) null else digits.substring(digits.length - 10)
    }

    private fun DuplicateCandidate.isCandidate() = isActive && mergedIntoSyncId.isNullOrEmpty() && normalizedName(fullName).isNotEmpty()

    fun compare(a: DuplicateCandidate, b: DuplicateCandidate): DuplicateReason {
        if (!a.isCandidate() || !b.isCandidate()) return DuplicateReason.None
        if (a.syncId.isNotEmpty() && a.syncId.equals(b.syncId, ignoreCase = true)) return DuplicateReason.None
        if (normalizedName(a.fullName) != normalizedName(b.fullName)) return DuplicateReason.None
        val birth = a.birthDate != null && a.birthDate.take(10) == b.birthDate?.take(10)
        val phoneA = normalizedPhone(a.phone)
        val phone = phoneA != null && phoneA == normalizedPhone(b.phone)
        return when {
            birth && phone -> DuplicateReason.BirthDateAndPhone
            birth -> DuplicateReason.BirthDate
            phone -> DuplicateReason.Phone
            else -> DuplicateReason.None
        }
    }

    /** Los pares en que está alguno de [involving], cada par una vez, ordenados por `SyncId` en minúsculas. */
    fun pairs(patients: List<DuplicateCandidate>, involving: Set<String>): List<DuplicatePair> {
        val wanted = involving.map { it.lowercase(Locale.ROOT) }.toSet()
        val candidates = patients.filter { it.isCandidate() }.groupBy { normalizedName(it.fullName) }
        val pairs = ArrayList<DuplicatePair>()
        for (group in candidates.values) {
            if (group.size < 2) continue
            for (i in group.indices) {
                for (j in i + 1 until group.size) {
                    val a = group[i].syncId.lowercase(Locale.ROOT)
                    val b = group[j].syncId.lowercase(Locale.ROOT)
                    if (a !in wanted && b !in wanted) continue
                    val reason = compare(group[i], group[j])
                    if (reason != DuplicateReason.None) pairs += DuplicatePair(minOf(a, b), maxOf(a, b), reason)
                }
            }
        }
        return pairs.distinct().sortedWith(compareBy({ it.first }, { it.second }))
    }
}
