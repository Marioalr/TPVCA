package io.github.marioalr.tpvca.ui

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * Espera a que se cumpla [condition], dejando avanzar a Compose, al hilo principal de Robolectric (que aquí no avanza solo) y a los hilos de fondo, que leen y escriben de verdad.
 *
 * **Sin un tope corto de reloj.** Antes cada prueba esperaba 5 o 10 s de reloj; en la CI `test` corría en paralelo con `lint`, que ocupa todos los núcleos del equipo compartido, y una lectura de 1 s tardaba más de
 * 10: la prueba fallaba sin que nada estuviera mal (`ClinicDataTabletTest`, `InventoryTabletTest`, `PhoneAppTest`). Ahora se espera a la condición; el único tope, [SAFETY_MINUTES], está para que una
 * condición que nunca se cumple falle con su mensaje en lugar de que la CI corte el trabajo a los 30 minutos.
 */
fun ComposeContentTestRule.waitForCondition(what: String = "la condición", condition: () -> Boolean) {
    val giveUp = System.nanoTime() + TimeUnit.MINUTES.toNanos(SAFETY_MINUTES)
    while (true) {
        waitForIdle()
        if (condition()) return
        if (System.nanoTime() > giveUp) throw AssertionError("No se cumplió $what")
        mainClock.advanceTimeBy(STEP_MILLIS)
        ShadowLooper.idleMainLooper(STEP_MILLIS, TimeUnit.MILLISECONDS)
        // Le da su turno a los hilos de fondo: es la espera de la condición, no una pausa fija
        Thread.sleep(POLL_MILLIS)
    }
}

private const val SAFETY_MINUTES = 3L
private const val STEP_MILLIS = 100L
private const val POLL_MILLIS = 5L
