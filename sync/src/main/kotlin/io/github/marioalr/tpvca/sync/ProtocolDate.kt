package io.github.marioalr.tpvca.sync

import java.time.DateTimeException
import java.time.LocalDateTime

/**
 * Las fechas del protocolo: `yyyy-MM-dd HH:mm:ss.fffffff`, siempre de 27 caracteres, sin zona y sin conversión. Con ese ancho fijo,
 * el orden del texto es el orden del tiempo.
 */
object ProtocolDate {
    /** El nulo de una fecha obligatoria (`DateTime.MinValue` en la PC). */
    const val MIN: String = "0001-01-01 00:00:00.0000000"

    private val SHAPE = Regex("""^(\d{4})-(\d{2})-(\d{2})(?:[ T](\d{1,2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?)?(?:Z)?$""")

    /** La fecha que se lee, o `null` si no es una fecha. Acepta lo que acepta `TursoSyncService.ParseDate` (con T, sin fracción, sin hora). */
    fun parse(text: String?): LocalDateTime? {
        val match = SHAPE.matchEntire(text?.trim().orEmpty()) ?: return null
        val (y, mo, d, h, mi, s, f) = match.destructured
        return try {
            LocalDateTime.of(
                y.toInt(), mo.toInt(), d.toInt(),
                h.ifEmpty { "0" }.toInt(), mi.ifEmpty { "0" }.toInt(), s.ifEmpty { "0" }.toInt(),
                f.padEnd(9, '0').toInt(),
            )
        } catch (_: DateTimeException) {
            null
        }
    }

    fun format(value: LocalDateTime): String {
        val ticks = (value.nano / 100).toString().padStart(7, '0')
        return "%04d-%02d-%02d %02d:%02d:%02d.%s".format(
            java.util.Locale.ROOT, value.year, value.monthValue, value.dayOfMonth, value.hour, value.minute, value.second, ticks,
        )
    }

    /** La forma de 27 caracteres; lo que no se lee vale [MIN], como en la PC. */
    fun canonical(text: String?): String = parse(text)?.let(::format) ?: MIN
}
