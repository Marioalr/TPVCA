package io.github.marioalr.tpvca.sync.rules

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/** Los folios que llevan un consecutivo: EXP-2026-001 (una serie por año, 3 dígitos) y TKT-20261009-0001 (una por día, 4). */
enum class FolioKind { Patient, Sale }

/** Un folio hecho sin conexión. */
data class LetteredFolio(val kind: FolioKind, val series: String, val deviceCode: String, val number: Int)

/** Cómo se arma cada folio, sin base, red ni reloj (`Services/Folios/FolioFactory.cs`, `golden/folios.json`). */
object FolioFactory {
    private val DEVICE_CODE = Regex("^[PTC][0-9]{1,4}$")
    private val LETTERED = Regex("^(EXP|TKT)-([0-9]{4}|[0-9]{8})-([PTC][0-9]{1,4})-([0-9]{1,9})$", RegexOption.IGNORE_CASE)

    fun series(kind: FolioKind, date: LocalDate): String =
        if (kind == FolioKind.Patient) "EXP-%04d".format(Locale.ROOT, date.year) else "TKT-%04d%02d%02d".format(Locale.ROOT, date.year, date.monthValue, date.dayOfMonth)

    fun digits(kind: FolioKind): Int = if (kind == FolioKind.Patient) 3 else 4

    fun format(kind: FolioKind, series: String, number: Int): String = "$series-${number.toString().padStart(digits(kind), '0')}"

    fun letteredSeries(series: String, deviceCode: String): String = "$series-$deviceCode"

    /** P1, T1, C2… X es de los rechazados y nunca numera. */
    fun isDeviceCode(code: String?): Boolean = code != null && DEVICE_CODE.matches(code)

    /** El número de [folio] en [series]: solo si es la serie, un guion y dígitos. */
    fun numberIn(series: String, folio: String?): Int? {
        val text = folio?.trim() ?: return null
        if (text.length <= series.length + 1 || !text.startsWith(series, ignoreCase = true) || text[series.length] != '-') return null
        val digits = text.substring(series.length + 1)
        if (digits.any { it !in '0'..'9' }) return null
        return digits.toIntOrNull()
    }

    fun maxIn(series: String, folios: Iterable<String?>): Int = folios.mapNotNull { numberIn(series, it) }.maxOrNull() ?: 0

    fun local(kind: FolioKind, date: LocalDate, existing: Iterable<String?>): String {
        val series = series(kind, date)
        return format(kind, series, maxIn(series, existing) + 1)
    }

    fun online(kind: FolioKind, date: LocalDate, taken: Int): String = format(kind, series(kind, date), taken)

    data class Offline(val folio: String, val letteredSeries: String, val number: Int)

    /** Sin conexión: el código del equipo y el mayor entre el último guardado fuera de la base y el máximo local, + 1. */
    fun offline(kind: FolioKind, date: LocalDate, deviceCode: String, existing: Iterable<String?>, savedLast: Int): Offline {
        require(isDeviceCode(deviceCode)) { "'$deviceCode' no es un código de equipo." }
        val lettered = letteredSeries(series(kind, date), deviceCode)
        val number = maxOf(savedLast, maxIn(lettered, existing)) + 1
        return Offline(format(kind, lettered, number), lettered, number)
    }

    fun parseLettered(folio: String?): LetteredFolio? {
        val match = LETTERED.matchEntire(folio?.trim().orEmpty()) ?: return null
        val (kindText, period, code, number) = match.destructured
        val kind = if (kindText.equals("EXP", ignoreCase = true)) FolioKind.Patient else FolioKind.Sale
        if (period.length != (if (kind == FolioKind.Patient) 4 else 8)) return null
        return LetteredFolio(kind, (if (kind == FolioKind.Patient) "EXP-" else "TKT-") + period, code.uppercase(Locale.ROOT), number.toInt())
    }

    /** La nota de consulta: CNS sale de la hora; sin conexión lleva la letra. Nunca se regulariza. */
    fun consultation(localNow: LocalDateTime, offlineDeviceCode: String? = null): String {
        val day = "%04d%02d%02d".format(Locale.ROOT, localNow.year, localNow.monthValue, localNow.dayOfMonth)
        val time = "%02d%02d%02d".format(Locale.ROOT, localNow.hour, localNow.minute, localNow.second)
        return if (isDeviceCode(offlineDeviceCode)) "CNS-$day-$offlineDeviceCode-$time" else "CNS-$day-$time"
    }
}
