package io.github.marioalr.tpvca.ui.phone

import io.github.marioalr.tpvca.domain.agenda.AgendaRules
import io.github.marioalr.tpvca.domain.model.BusinessPeriod
import io.github.marioalr.tpvca.domain.text.MexicanText
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Solo textos de fecha y de dinero para las pantallas: ninguna cifra sale de aquí. Los meses abreviados no dependen de los datos de la cultura del equipo (unos dicen
 * "oct" y otros "oct."). **Las fechas del consultorio se muestran como están guardadas, sin convertirlas de zona.**
 */
object PhoneFormat {
    private val MONTHS = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")
    private val LONG_MONTHS = listOf("enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre")
    private val DAYS = listOf("Lun", "Mar", "Mié", "Jue", "Vie", "Sáb", "Dom")
    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

    /** El importe de la PC sin ".00" en lo que no trae centavos: "$3,480", "$165.60". Nada se redondea. */
    fun money(value: BigDecimal): String = MexicanText.currency(value).removeSuffix(".00")

    fun shortDate(date: LocalDate, today: LocalDate? = null): String {
        val text = "${date.dayOfMonth} ${MONTHS[date.monthValue - 1]}"
        return if (today != null && date.year != today.year) "$text ${date.year}" else text
    }

    fun clock(date: LocalDateTime): String = date.format(CLOCK)

    /** "Hoy 09:20", "28 oct 10:55". */
    fun dayAndTime(date: LocalDateTime, today: LocalDate): String =
        (if (date.toLocalDate() == today) "Hoy" else shortDate(date.toLocalDate(), today)) + " " + clock(date)

    fun weekDay(date: LocalDate): String = DAYS[date.dayOfWeek.value - 1]

    /** "Noviembre 2026". */
    fun monthYear(date: LocalDate): String = LONG_MONTHS[date.monthValue - 1].replaceFirstChar { it.uppercase() } + " ${date.year}"

    fun greeting(hour: Int): String = when {
        hour < 12 -> "Buenos días"
        hour < 19 -> "Buenas tardes"
        else -> "Buenas noches"
    }

    /** Un día: "Lunes 3 de noviembre". Un rango: "1 – 31 de octubre" o "28 de oct – 3 de nov". */
    fun periodTitle(period: BusinessPeriod): String {
        val from = period.from
        val to = period.to.minusDays(1)
        return when {
            from == to -> AgendaRules.panelTitle(from)
            from.year != to.year -> "${from.dayOfMonth} ${MONTHS[from.monthValue - 1]} ${from.year} – ${to.dayOfMonth} ${MONTHS[to.monthValue - 1]} ${to.year}"
            from.month == to.month -> "${from.dayOfMonth} – ${to.dayOfMonth} de ${LONG_MONTHS[to.monthValue - 1]}"
            else -> "${from.dayOfMonth} de ${MONTHS[from.monthValue - 1]} – ${to.dayOfMonth} de ${MONTHS[to.monthValue - 1]}"
        }
    }

    /** La insignia de sincronización: "Hace un momento", "Hace 2 min", "Hace 3 h", "Hace 2 días". Un reloj atrasado no da negativos. */
    fun since(last: Instant?, now: Instant): String {
        if (last == null) return "Sin sincronizar"
        val minutes = Duration.between(last, now).toMinutes()
        return when {
            minutes < 1 -> "Hace un momento"
            minutes < 60 -> "Hace $minutes min"
            minutes < 24 * 60 -> "Hace ${minutes / 60} h"
            minutes < 2 * 24 * 60 -> "Hace 1 día"
            else -> "Hace ${minutes / (24 * 60)} días"
        }
    }

    /** "1.2 MB" o "340 KB", para la lista de resultados. */
    fun fileSize(bytes: Long): String =
        if (bytes >= 1_000_000) "${(bytes / 100_000) / 10.0} MB" else "${maxOf(1, bytes / 1000)} KB"
}
