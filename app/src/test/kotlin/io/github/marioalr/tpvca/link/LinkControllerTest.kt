package io.github.marioalr.tpvca.link

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.marioalr.tpvca.domain.devices.DeviceRole
import io.github.marioalr.tpvca.sync.InMemorySyncStore
import io.github.marioalr.tpvca.sync.engine.SyncStore
import io.github.marioalr.tpvca.sync.hrana.HranaConnection
import io.github.marioalr.tpvca.sync.hrana.ResultSet
import io.github.marioalr.tpvca.sync.hrana.Snapshot
import io.github.marioalr.tpvca.sync.hrana.Statement
import io.github.marioalr.tpvca.sync.link.CredentialVault
import io.github.marioalr.tpvca.sync.link.DeviceCredential
import io.github.marioalr.tpvca.sync.link.LinkFlow
import io.github.marioalr.tpvca.sync.link.LinkProblem
import io.github.marioalr.tpvca.sync.link.LinkState
import io.github.marioalr.tpvca.sync.link.LocalDatabase
import io.github.marioalr.tpvca.sync.link.StoredCredential
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class LinkControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val states = MutableStateFlow<LinkState?>(null)
    private var now = 0L
    private val lock = AppLock({ now })
    private var secure = true
    private var failLoad = false
    private var heartbeats = 0
    private var stored: StoredCredential = StoredCredential.Empty

    private val vault = object : CredentialVault {
        override fun load(): StoredCredential {
            if (failLoad) throw java.io.IOException("ruta/secreta/credencial.bin")
            return stored
        }

        override fun save(credential: DeviceCredential) {
            stored = StoredCredential.Present(credential)
        }

        override fun clear() {
            stored = StoredCredential.Empty
        }
    }

    private val database = object : LocalDatabase {
        private val store = InMemorySyncStore()

        override fun store(): SyncStore = store

        override fun erase() = Unit
    }

    private val connection = object : HranaConnection {
        override fun query(statements: List<Statement>): List<ResultSet> {
            heartbeats += statements.count { it.sql.startsWith("INSERT INTO RegisteredDevices") }
            return statements.map { ResultSet(emptyList()) }
        }

        override fun snapshot(before: List<Statement>, statements: List<Statement>) = Snapshot(before.map { ResultSet(emptyList()) }, statements.map { ResultSet(emptyList()) })
    }

    private val flow = LinkFlow(
        vault,
        database,
        PreferencesLinkNotices(ApplicationProvider.getApplicationContext()),
        { secure },
        "0.1.0",
        "Equipo",
        {},
        connect = { _, _ -> connection },
        onState = { states.value = it },
    )

    private val controller = LinkController(flow, states, lock, { secure }, scope, dispatcher)

    @Test
    fun alAbrirSinVincularQuedaEnNoVinculado() {
        assertNull(controller.state.value)
        controller.onForeground()
        scope.runCurrent()
        assertEquals(LinkState.NotLinked(), controller.state.value)
        controller.onBackground()
    }

    @Test
    fun unErrorLocalNoCierraLaAppNiRepiteElTextoDeLaExcepcion() {
        failLoad = true
        controller.onForeground()
        scope.runCurrent()
        val state = controller.state.value as LinkState.CloudProblem
        assertEquals(LinkController.LOCAL_ERROR, state.message)
        assertFalse(state.message.contains("ruta"))
        controller.onBackground()
    }

    @Test
    fun alVincularNoTerminaEnLaPantallaDeBloqueo() {
        controller.onForeground()
        scope.runCurrent()
        assertTrue(controller.locked.value)
        controller.link("https://consultorio-ejemplo.turso.io", "token-de-prueba", "", DeviceRole.AndroidMobileAdmin)
        assertFalse(controller.locked.value)
        controller.onBackground()
    }

    @Test
    fun vincularSinBloqueoDePantallaLoDice() {
        secure = false
        controller.onForeground()
        scope.runCurrent()
        assertFalse(controller.deviceSecure.value)
        controller.link("https://consultorio-ejemplo.turso.io", "token-de-prueba", "", DeviceRole.AndroidTablet)
        scope.runCurrent()
        assertEquals(LinkState.NotLinked(LinkProblem.NoSecureLock), controller.state.value)
        controller.onBackground()
    }

    @Test
    fun alVolverSeRevisaSiActivoElBloqueoDePantalla() {
        secure = false
        controller.onForeground()
        assertFalse(controller.deviceSecure.value)
        controller.onBackground()
        secure = true
        controller.onForeground()
        assertTrue(controller.deviceSecure.value)
        controller.onBackground()
    }

    @Test
    fun enSegundoPlanoLaCadenciaSeDetiene() {
        controller.onForeground()
        scope.runCurrent()
        controller.onBackground()
        val before = scope.testScheduler.currentTime
        scope.advanceTimeBy(60_000)
        scope.runCurrent()
        assertEquals(0, heartbeats)
        assertTrue(scope.testScheduler.currentTime > before)
    }

    @Test
    fun a5MinEnSegundoPlanoSeBloquea() {
        controller.onForeground()
        lock.unlock()
        controller.onBackground()
        now += AppLock.TIMEOUT_MILLIS
        controller.onForeground()
        assertTrue(controller.locked.value)
        controller.onBackground()
    }
}
