package io.github.marioalr.tpvca.link

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.marioalr.tpvca.domain.devices.DeviceRole
import io.github.marioalr.tpvca.sync.InMemorySyncStore
import io.github.marioalr.tpvca.sync.engine.SyncStore
import io.github.marioalr.tpvca.sync.Statements
import io.github.marioalr.tpvca.sync.SyncContract
import io.github.marioalr.tpvca.sync.hrana.HranaConnection
import io.github.marioalr.tpvca.sync.hrana.HranaValue
import io.github.marioalr.tpvca.sync.hrana.Row
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
import java.util.TreeMap

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
    private var registryRow: List<String?>? = null

    @Volatile
    private var syncs = 0

    /** Una nube que no contesta hasta soltarla. */
    @Volatile
    private var hang: java.util.concurrent.CountDownLatch? = null

    private fun syncRequests() = syncs

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

    private val cloudMeta = mapOf(
        "schema" to SyncContract.bundledSchema.fingerprint,
        "clinicId" to "clinica-1",
        "generation" to "gen-1",
        "generationReady" to "gen-1",
        "minClientProtocol" to "2",
        "fullUploadAtUtc" to "2026-10-01 10:00:00.000",
        "manifest" to SyncContract.bundledSchemaText(),
    )

    private fun row(vararg values: Pair<String, String?>): Row =
        TreeMap<String, HranaValue>(String.CASE_INSENSITIVE_ORDER).apply { for ((k, v) in values) put(k, v?.let(HranaValue::Text) ?: HranaValue.Null) }

    private fun answer(statement: Statement): ResultSet = when (statement.sql) {
        Statements.META_ALL -> ResultSet(cloudMeta.map { row("Key" to it.key, "Value" to it.value) })
        Statements.REGISTRY_OWN -> ResultSet(listOfNotNull(registryRow?.let { row("DeviceCode" to it[0], "Role" to it[1], "RevokedAtUtc" to it[2], "RevokedReason" to it[3]) }))
        Statements.HANDSHAKE -> ResultSet(listOf(row("now" to "2026-11-03 18:00:00.000", "wm" to "2026-11-03 17:59:55.000", "clinicId" to "clinica-1", "generation" to "gen-1", "generationReady" to "gen-1", "fullUploadAtUtc" to "2026-10-01 10:00:00.000")))
        Statements.GENERATION -> ResultSet(listOf(row("generation" to "gen-1")))
        Statements.META_SCHEMA -> ResultSet(listOf(row("Value" to SyncContract.bundledSchema.fingerprint)))
        else -> ResultSet(emptyList())
    }

    private val connection = object : HranaConnection {
        override fun query(statements: List<Statement>): List<ResultSet> {
            heartbeats += statements.count { it.sql.startsWith("INSERT INTO RegisteredDevices") }
            return statements.map(::answer)
        }

        override fun snapshot(before: List<Statement>, statements: List<Statement>): Snapshot {
            syncs++
            hang?.await(5, java.util.concurrent.TimeUnit.SECONDS)
            return Snapshot(before.map(::answer), statements.map(::answer))
        }
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
        assertEquals(LinkState.LocalError, controller.state.value)
        // Sin saber qué hay guardado: cerrado aunque el rol no se conozca, y nada se puede borrar
        assertEquals(Gate.Locked, gateFor(controller.state.value, controller.role, controller.locked.value, controller.deviceSecure.value))
        controller.forget()
        scope.runCurrent()
        assertEquals(LinkState.LocalError, controller.state.value)
        // Era pasajero: la cadencia lo vuelve a leer
        failLoad = false
        scope.advanceTimeBy(LinkController.TICK_MILLIS + 1)
        assertEquals(LinkState.NotLinked(), controller.state.value)
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
    fun alTerminarBienDiceCuandoYLasPantallasVuelvenALeer() {
        registryRow = listOf("C-1", "AndroidMobileAdmin", null, null)
        controller.onForeground()
        scope.runCurrent()
        assertNull(controller.lastSuccess.value)
        assertEquals(0L, controller.dataVersion.value)
        controller.link("https://consultorio-ejemplo.turso.io", "token-de-prueba", "", DeviceRole.AndroidMobileAdmin)
        scope.runCurrent()
        controller.refreshNow()
        scope.runCurrent()
        assertTrue(controller.state.value is LinkState.Linked)
        assertTrue(controller.lastSuccess.value != null)
        assertTrue(controller.dataVersion.value > 0L)
        val version = controller.dataVersion.value
        // Sin otra sincronización que termine bien, nadie vuelve a leer
        scope.advanceTimeBy(LinkController.TICK_MILLIS + 1)
        assertEquals(version, controller.dataVersion.value)
        controller.onBackground()
    }

    @Test
    fun laPuertaSigueAlRolAprobadoYNoAlPedido() {
        registryRow = listOf("C1", "AndroidMobileAdmin", null, null)
        controller.onForeground()
        scope.runCurrent()
        controller.link("https://consultorio-ejemplo.turso.io", "token-de-prueba", "", DeviceRole.AndroidTablet)
        scope.runCurrent()
        assertEquals(DeviceRole.AndroidMobileAdmin, controller.role)
        // Se vinculó aquí y la persona está presente; al pasar 5 min afuera, se bloquea como celular
        controller.onBackground()
        now += AppLock.TIMEOUT_MILLIS
        controller.onForeground()
        assertEquals(Gate.Locked, gateFor(controller.state.value, controller.role, controller.locked.value, controller.deviceSecure.value))
        controller.onBackground()
    }

    private fun linkedController() {
        registryRow = listOf("T1", "AndroidTablet", null, null)
        controller.onForeground()
        scope.runCurrent()
        controller.link("https://consultorio-ejemplo.turso.io", "token-de-prueba", "", DeviceRole.AndroidTablet)
        scope.runCurrent()
        assertTrue(controller.state.value is LinkState.Linked)
        controller.onBackground()
    }

    @Test
    fun variosGuardadosSeguidosSonUnaSolaSincronizacion() {
        linkedController()
        val before = syncRequests()
        controller.noteLocalWrite()
        scope.advanceTimeBy(LinkController.AFTER_SAVE_MILLIS - 1)
        controller.noteLocalWrite()
        scope.advanceTimeBy(LinkController.AFTER_SAVE_MILLIS - 1)
        scope.runCurrent()
        assertEquals("Espera al último", before, syncRequests())
        scope.advanceTimeBy(2)
        scope.runCurrent()
        assertEquals("Una sola", before + 1, syncRequests())
    }

    /** Con hilos de verdad: el tiempo de la espera y el de la red son independientes. */
    private fun realController() =
        LinkController(flow, states, lock, { true }, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO), kotlinx.coroutines.Dispatchers.IO)

    @Test
    fun laSincronizacionRapidaTerminaATiempo() {
        linkedController()
        val before = syncRequests()
        assertTrue(kotlinx.coroutines.runBlocking { realController().quickSync() })
        assertEquals(before + 1, syncRequests())
        assertFalse(flow.quickSyncHanging)
    }

    @Test
    fun laSincronizacionRapidaColgadaNoDetieneElFolio() {
        linkedController()
        val release = java.util.concurrent.CountDownLatch(1)
        hang = release
        val finished = kotlinx.coroutines.runBlocking { realController().quickSync(waitMillis = 50) }
        assertFalse(finished)
        assertTrue("El folio ya no espera a la red", flow.quickSyncHanging)
        release.countDown()
        // Cuando por fin termina, se quita
        val deadline = System.currentTimeMillis() + 5_000
        while (flow.quickSyncHanging && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertFalse(flow.quickSyncHanging)
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
