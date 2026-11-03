package io.github.marioalr.tpvca.domain

import java.math.RoundingMode
import java.time.ZoneId
import java.util.Locale

/** Formato y reloj del consultorio, iguales a los de la PC (.NET con es-MX). */
object Clinic {
    /** Textos, orden y formatos: español de México. */
    val LOCALE: Locale = Locale.forLanguageTag("es-MX")

    /** "Hoy" y las horas `clinicLocal` del contrato. */
    val ZONE: ZoneId = ZoneId.of("America/Mexico_City")

    /** `Math.Round` de decimal en .NET redondea al par. */
    val ROUNDING: RoundingMode = RoundingMode.HALF_EVEN
}
