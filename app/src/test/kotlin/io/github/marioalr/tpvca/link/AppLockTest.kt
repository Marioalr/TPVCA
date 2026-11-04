package io.github.marioalr.tpvca.link

import io.github.marioalr.tpvca.domain.devices.DeviceRole
import io.github.marioalr.tpvca.sync.link.LinkState
import io.github.marioalr.tpvca.ui.link.CameraBinding
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
    fun elBloqueoSigueAlRolConElQueTrabajaNoAlQuePidio() {
        // Pidió tablet y la PC lo aprobó como celular: role ya es el aprobado (LinkFlow.role)
        assertEquals(Gate.Locked, gateFor(linked, DeviceRole.AndroidMobileAdmin, locked = true, deviceSecure = true))
        // Pidió celular y quedó como tablet
        val tablet = LinkState.Linked(DeviceRole.AndroidTablet, "T1", "")
        assertEquals(Gate.Open, gateFor(tablet, DeviceRole.AndroidTablet, locked = true, deviceSecure = true))
    }

    @Test
    fun sinBloqueoDePantallaNoSeMuestraNadaDeUnEquipoVinculado() {
        assertEquals(Gate.NoSecureLock, gateFor(linked, DeviceRole.AndroidMobileAdmin, locked = false, deviceSecure = false))
        assertEquals(Gate.NoSecureLock, gateFor(linked, DeviceRole.AndroidTablet, locked = false, deviceSecure = false))
        assertEquals(Gate.Open, gateFor(LinkState.NotLinked(), null, locked = true, deviceSecure = false))
    }

    @Test
    fun sinSaberQueHayGuardadoQuedaCerrado() {
        assertEquals(Gate.Locked, gateFor(LinkState.LocalError, null, locked = true, deviceSecure = true))
        assertEquals(Gate.NoSecureLock, gateFor(LinkState.LocalError, null, locked = true, deviceSecure = false))
        assertEquals("Ya desbloqueó: ve el error, no datos", Gate.Open, gateFor(LinkState.LocalError, null, locked = false, deviceSecure = true))
        assertEquals("Con credencial conocida, el rol decide", Gate.Locked, gateFor(LinkState.LocalError, DeviceRole.AndroidMobileAdmin, locked = true, deviceSecure = true))
    }

    @Test
    fun laCamaraNoSeEnciendeSiYaSalioYSeApagaAlSalir() {
        val calls = ArrayList<String>()
        val late = CameraBinding()
        late.leave()
        late.bindWhenReady({ calls += "unbind" }) { calls += "bind" }
        assertEquals(emptyList<String>(), calls)

        val normal = CameraBinding()
        normal.bindWhenReady({ calls += "unbind" }) { calls += "bind" }
        normal.leave()
        assertEquals(listOf("bind", "unbind"), calls)
    }

    @Test
    fun mientrasCargaNoSeMuestraNada() {
        assertEquals(Gate.Loading, gateFor(null, null, locked = true, deviceSecure = true))
    }
}
