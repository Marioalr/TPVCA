package io.github.marioalr.tpvca.link

import io.github.marioalr.tpvca.domain.devices.DeviceRole
import io.github.marioalr.tpvca.sync.link.LinkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan §4.8: al abrir y después de 5 min en segundo plano. */
class AppLockTest {
    private var now = 1_000L
    private val lock = AppLock({ now })
    private val minute = 60_000L

    @Test
    fun alAbrirEstaBloqueada() {
        assertTrue(lock.locked.value)
        lock.onForeground()
        assertTrue(lock.locked.value)
    }

    @Test
    fun desbloqueadaSigueAsiMenosDe5Min() {
        lock.unlock()
        lock.onBackground()
        now += 5 * minute - 1
        lock.onForeground()
        assertFalse(lock.locked.value)
    }

    @Test
    fun a5MinEnSegundoPlanoSeBloquea() {
        lock.unlock()
        lock.onBackground()
        now += 5 * minute
        lock.onForeground()
        assertTrue(lock.locked.value)
    }

    @Test
    fun cadaSalidaCuentaDesdeCero() {
        lock.unlock()
        lock.onBackground()
        now += 4 * minute
        lock.onForeground()
        now += 10 * minute
        lock.onBackground()
        now += minute
        lock.onForeground()
        assertFalse("10 min al frente no cuentan", lock.locked.value)
    }

    private val linked = LinkState.Linked(DeviceRole.AndroidMobileAdmin, "C1", "")

    @Test
    fun soloElCelularVinculadoSeBloquea() {
        assertEquals(Gate.Locked, gateFor(linked, DeviceRole.AndroidMobileAdmin, locked = true, deviceSecure = true))
        assertEquals(Gate.Open, gateFor(linked, DeviceRole.AndroidMobileAdmin, locked = false, deviceSecure = true))
        assertEquals("El plan pide el bloqueo en el celular", Gate.Open, gateFor(linked, DeviceRole.AndroidTablet, locked = true, deviceSecure = true))
        assertEquals("Sin vincular no hay nada que proteger", Gate.Open, gateFor(LinkState.NotLinked(), null, locked = true, deviceSecure = true))
    }

    @Test
    fun sinBloqueoDePantallaNoSeMuestraNadaDeUnEquipoVinculado() {
        assertEquals(Gate.NoSecureLock, gateFor(linked, DeviceRole.AndroidMobileAdmin, locked = false, deviceSecure = false))
        assertEquals(Gate.NoSecureLock, gateFor(linked, DeviceRole.AndroidTablet, locked = false, deviceSecure = false))
        assertEquals(Gate.Open, gateFor(LinkState.NotLinked(), null, locked = true, deviceSecure = false))
    }

    @Test
    fun mientrasCargaNoSeMuestraNada() {
        assertEquals(Gate.Loading, gateFor(null, null, locked = true, deviceSecure = true))
    }
}
