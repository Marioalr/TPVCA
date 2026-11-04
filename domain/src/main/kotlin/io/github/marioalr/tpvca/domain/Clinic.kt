package io.github.marioalr.tpvca.domain

import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.zone.ZoneRulesException
import java.util.Locale

/** Formato y reloj del consultorio, iguales a los de la PC (.NET con es-MX). */
object Clinic {
    /** Textos, orden y formatos: español de México. */
    val LOCALE: Locale = Locale.forLanguageTag("es-MX")

    /**
     * "Hoy" y las horas `clinicLocal` del contrato: America/Mexico_City. Si el equipo no trae esa zona en sus datos, UTC−6
     * (la Ciudad de México no tiene horario de verano desde 2022), plan §7.2.
     */
    val ZONE: ZoneId = zoneOrFallback("America/Mexico_City")

    /** `Math.Round` de decimal en .NET redondea al par. */
    val ROUNDING: RoundingMode = RoundingMode.HALF_EVEN

    /** La zona [id], o UTC−6 si el equipo no la conoce. */
    fun zoneOrFallback(id: String): ZoneId = try {
        ZoneId.of(id)
    } catch (_: ZoneRulesException) {
        ZoneOffset.ofHours(-6)
    }

    /** El día del consultorio en el instante [now] (`ClinicClock.Today`): las reglas lo reciben, nunca leen el reloj. */
    fun today(now: Instant, zone: ZoneId = ZONE): LocalDate = now.atZone(zone).toLocalDate()

    /** La hora del consultorio en el instante [now] (`ClinicClock.Now`). */
    fun localNow(now: Instant, zone: ZoneId = ZONE): LocalDateTime = now.atZone(zone).toLocalDateTime()
}
