package io.github.marioalr.tpvca.sync.link

import io.github.marioalr.tpvca.domain.devices.DeviceRole
import io.github.marioalr.tpvca.sync.InMemorySyncStore
import io.github.marioalr.tpvca.sync.MutableClock
import io.github.marioalr.tpvca.sync.SqlCloud
import io.github.marioalr.tpvca.sync.Statements
import io.github.marioalr.tpvca.sync.SyncContract
import io.github.marioalr.tpvca.sync.engine.SyncEngine
import io.github.marioalr.tpvca.sync.engine.SyncStore
import io.github.marioalr.tpvca.sync.hrana.text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import io.github.marioalr.tpvca.sync.engine.StoreChanges
import io.github.marioalr.tpvca.sync.hrana.HranaConnection
import io.github.marioalr.tpvca.sync.hrana.Snapshot
import io.github.marioalr.tpvca.sync.hrana.Statement
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** La cadencia del paso 8.24 en la tablet (K8): la revisión dentro de los ticks, el latido dentro de la revisión, la marca de cambios que sincroniza y el celular que no revisa. */
class LinkFlowK8Test {
    private val cloud = SqlCloud()
    private val clock = MutableClock(Instant.parse("2026-11-08T15:00:00Z"))

    private inner class Vault : CredentialVault {
        var stored: StoredCredential = StoredCredential.Empty

        override fun load() = stored

        override fun save(credential: DeviceCredential) {
            stored = StoredCredential.Present(credential)
        }

        override fun clear() {
            stored = StoredCredential.Empty
        }
    }

    private inner class Database : LocalDatabase {
        var store = InMemorySyncStore()

        override fun store(): SyncStore = store

        override fun erase() {
            store = InMemorySyncStore()
        }
    }

    private class Notices : LinkNotices {
        var saved: Revocation? = null

        override fun revocation() = saved

        override fun saveRevocation(reason: String?) {
            saved = Revocation(reason)
        }

        override fun clearRevocation() {
            saved = null
        }
    }

    private val database = Database()
    private val checks = ArrayList<HandoffCheck>()
    private var role = "AndroidTablet"

    /** Mientras tiene una espera puesta, la sincronización se queda a media bajada (`snapshot`): así se ve qué sigue corriendo y qué no. */
    @Volatile
    private var holdSync: CountDownLatch? = null
    private val syncHeld = CountDownLatch(1)
    private val connection = object : HranaConnection {
        override fun query(statements: List<Statement>) = cloud.connection.query(statements)

        override fun snapshot(before: List<Statement>, statements: List<Statement>): Snapshot {
            holdSync?.let { hold ->
                syncHeld.countDown()
                hold.await(10, TimeUnit.SECONDS)
            }
            return cloud.connection.snapshot(before, statements)
        }
    }

    private fun flow() = LinkFlow(
        Vault(), database, Notices(), { true }, "1.0.0", "Tablet", {},
        connect = { _, _ -> connection },
        clock = clock,
        newId = { "device-b" },
        allowLoopback = true,
        onHandoffCheck = { checks += it },
    )

    private fun meta(key: String, value: String) = cloud.admin("INSERT INTO _tpvc_meta (Key, Value) VALUES (?, ?) ON CONFLICT(Key) DO UPDATE SET Value = excluded.Value", key, value)

    @Before
    fun cloudReady() {
        cloud.createSchema()
        meta("schema", SyncContract.bundledSchema.fingerprint)
        meta("clinicId", "clinica-1")
        meta("protocol", "3")
        meta("minClientProtocol", "2")
        meta("manifest", SyncContract.bundledSchemaText())
        meta("generation", "gen-1")
        meta("generationReady", "gen-1")
        meta("fullUploadAtUtc", cloud.nowText())
    }

    private fun linked(): LinkFlow {
        cloud.admin("INSERT INTO DeviceRegistry (DeviceId, DeviceCode, Role, DisplayName, Model, PairedAtUtc) VALUES ('device-b', 'T1', ?, 'b', '', 'x')", role)
        return flow().also {
            it.restore()
            assertTrue(it.link("http://127.0.0.1/x", "token", "Consultorio", DeviceRole.fromName(role)!!) is LinkState.Linked)
            cloud.executed.clear()
        }
    }

    private fun sendToB(id: String) = cloud.admin(
        "INSERT INTO ConsultationHandoffs (Id, FromDeviceId, FromDeviceName, ToDeviceId, ToDeviceName, PatientSyncId, PatientName, Folio, Payload, PayloadVersion, State, CreatedAtUtc, UpdatedAtUtc) " +
            "VALUES (?, 'device-pc', 'pc', 'device-b', 'b', 'p1', 'Ana', 'CNS-1', '{}', 1, 'enviada', strftime('%Y-%m-%d %H:%M:%f','now'), strftime('%Y-%m-%d %H:%M:%f','now'))",
        id,
    )

    /** Una vuelta de `LinkController`: la revisión aparte, y luego la sincronización que toque. */
    private fun tick(flow: LinkFlow, after: Duration) {
        clock.advance(after)
        flow.checkHandoffs()
        flow.refresh(Refresh.TICK)
    }

    @Test
    fun laRevisionVaEnLosTicksConElLatidoDentroYAvisaLoRecibido() {
        val flow = linked()
        sendToB("envio-1")
        // La PC es compañera: con ella, en Pacientes, la revisión va cada 4 s
        cloud.admin("INSERT INTO RegisteredDevices (DeviceId, DeviceName, DeviceRole, AppVersion, LastSeenUtc, Platform, HandoffProtocol) VALUES ('device-pc', 'pc', 'WindowsDesktop', '1.43.0', strftime('%Y-%m-%d %H:%M:%f','now'), 'Windows', 1)")
        flow.setHandoffContext(inPatients = true, outgoingId = null)
        tick(flow, Duration.ofSeconds(1))
        assertEquals(1, checks.size)
        assertEquals(listOf("envio-1"), checks[0].result!!.incoming.map { it.id })
        assertNull(checks[0].problem)
        // Una sola petición: la revisión, la marca de cambios, el latido (lo acaba de escribir el enlace: aquí no se repite) y la lista de compañeros
        assertTrue(Statements.CHECK_INCOMING in cloud.executed)
        assertTrue(Statements.CHECK_PEERS_DEVICES in cloud.executed)
        assertFalse("El latido del enlace es de hace 1 s", Statements.HEARTBEAT_WRITE in cloud.executed)
        // Todavía no toca otra
        tick(flow, Duration.ofSeconds(2))
        assertEquals(1, checks.size)
        // A los 4 s, otra vez; la lista de compañeros solo cada 10 min
        cloud.executed.clear()
        tick(flow, Duration.ofSeconds(2))
        assertEquals(2, checks.size)
        assertFalse(Statements.CHECK_PEERS_DEVICES in cloud.executed)
        // A los 60 s el latido va dentro de la revisión
        cloud.executed.clear()
        tick(flow, Duration.ofSeconds(60))
        assertTrue(Statements.HEARTBEAT_WRITE in cloud.executed)
        assertTrue(Statements.CHECK_INCOMING in cloud.executed)
        assertEquals("device-b", cloud.admin("SELECT DeviceId FROM RegisteredDevices WHERE DeviceId = 'device-b'").single()["DeviceId"]?.text)
        assertFalse("Ningún DDL", cloud.executed.any { it.startsWith("CREATE") || it.startsWith("ALTER") })
    }

    @Test
    fun fueraDePacientesLaRevisionVaCada30s() {
        val flow = linked()
        flow.setHandoffContext(inPatients = false, outgoingId = null)
        tick(flow, Duration.ofSeconds(1))
        assertEquals(1, checks.size)
        tick(flow, Duration.ofSeconds(10))
        assertEquals(1, checks.size)
        tick(flow, Duration.ofSeconds(20))
        assertEquals(2, checks.size)
        // Al entrar a Pacientes se revisa enseguida
        flow.setHandoffContext(inPatients = true, outgoingId = "envio-x")
        tick(flow, Duration.ofSeconds(1))
        assertEquals(3, checks.size)
        assertEquals("", checks[2].result!!.outgoingState)
        assertTrue(checks[2].result!!.hadOutgoing)
    }

    @Test
    fun laMarcaDeCambiosDeOtroEquipoSincronizaEnEseMomento() {
        val flow = linked()
        assertNotNull(database.store.meta(SyncEngine.LAST_PULL_WATERMARK))
        // La PC sube un expediente y deja su marca de cambios después de la última bajada de la tablet
        cloud.clockOffsetSeconds = 10
        cloud.admin("INSERT INTO Patients (SyncId, UpdatedAtUtc, SyncedAtUtc, OriginDeviceId, FullName, RecordNumber) VALUES ('p-nueva', '2026-11-08 15:00:05.0000000', ?, 'device-pc', 'Ana', 'EXP-2026-001')", cloud.nowText())
        cloud.admin("INSERT INTO RegisteredDevices (DeviceId, DeviceName, DeviceRole, AppVersion, LastSeenUtc, Platform, HandoffProtocol, LastPushUtc) VALUES ('device-pc', 'pc', 'WindowsDesktop', '1.43.0', ?, 'Windows', 1, ?)", cloud.nowText(), cloud.nowText())
        flow.setHandoffContext(inPatients = true, outgoingId = null)
        tick(flow, Duration.ofSeconds(1))
        assertEquals(1L, database.store.rowCounts()["Patients"])
        val syncs = cloud.executed.count { it == Statements.HANDSHAKE }
        assertEquals(1, syncs)
        // La misma marca no dispara otra sincronización
        tick(flow, Duration.ofSeconds(5))
        assertEquals(1, cloud.executed.count { it == Statements.HANDSHAKE })
    }

    @Test
    fun elCelularNoRevisaConsultas() {
        role = "AndroidMobileAdmin"
        val flow = linked()
        flow.setHandoffContext(inPatients = true, outgoingId = null)
        tick(flow, Duration.ofSeconds(1))
        assertEquals(0, checks.size)
        assertFalse(Statements.CHECK_INCOMING in cloud.executed)
    }

    /** Regla «Cadencia»: la sincronización que corre (de la cadencia o del médico) no detiene la revisión ni el latido; mientras corre no se pide otra. */
    @Test
    fun laRevisionYElLatidoNoEsperanALaSincronizacion() {
        val flow = linked()
        cloud.admin("INSERT INTO RegisteredDevices (DeviceId, DeviceName, DeviceRole, AppVersion, LastSeenUtc, Platform, HandoffProtocol) VALUES ('device-pc', 'pc', 'WindowsDesktop', '1.43.0', strftime('%Y-%m-%d %H:%M:%f','now'), 'Windows', 1)")
        flow.setHandoffContext(inPatients = true, outgoingId = null)
        val hold = CountDownLatch(1)
        holdSync = hold
        val running = thread { flow.refresh(Refresh.USER) }
        try {
            assertTrue("La sincronización quedó a media bajada", syncHeld.await(5, TimeUnit.SECONDS))
            sendToB("envio-durante")
            clock.advance(Duration.ofSeconds(1))
            val done = CountDownLatch(1)
            thread {
                flow.checkHandoffs()
                done.countDown()
            }
            assertTrue("La revisión no espera el candado de la sincronización", done.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("envio-durante"), checks.single().result!!.incoming.map { it.id })
            // A los 60 s el latido va dentro de la revisión, todavía con la sincronización a medias
            cloud.executed.clear()
            clock.advance(Duration.ofSeconds(61))
            flow.checkHandoffs()
            assertEquals(2, checks.size)
            assertTrue(Statements.HEARTBEAT_WRITE in cloud.executed)
        } finally {
            hold.countDown()
            running.join(10_000)
            holdSync = null
        }
        assertTrue(flow.state is LinkState.Linked)
    }

    /** `ThrowIfAutomaticSyncPaused`: con la cuota agotada o el token rechazado la revisión no pregunta a la nube, y la pantalla recibe por qué. */
    @Test
    fun conLoAutomaticoEnPausaLaRevisionNoPreguntaYDicePorQue() {
        val flow = linked()
        flow.setHandoffContext(inPatients = true, outgoingId = null)
        database.store.apply(StoreChanges(meta = mapOf(SyncEngine.QUOTA_RETRY_AT to clock.instant().plusSeconds(3600).toString())))
        cloud.executed.clear()
        tick(flow, Duration.ofSeconds(1))
        assertEquals(HandoffPause.QUOTA, checks.single().problem)
        assertNull(checks.single().result)
        assertFalse(Statements.CHECK_INCOMING in cloud.executed)
        // A su paso, no en cada vuelta
        tick(flow, Duration.ofSeconds(1))
        assertEquals(1, checks.size)

        database.store.apply(StoreChanges(meta = mapOf(SyncEngine.AUTH_FIRST_401 to clock.instant().toString()), metaRemoved = setOf(SyncEngine.QUOTA_RETRY_AT)))
        tick(flow, Duration.ofSeconds(30))
        assertEquals(HandoffPause.TOKEN, checks.last().problem)
        assertFalse(Statements.CHECK_INCOMING in cloud.executed)

        // Sin pausa vuelve a preguntar
        database.store.apply(StoreChanges(metaRemoved = setOf(SyncEngine.AUTH_FIRST_401, SyncEngine.AUTH_FIRST_401_ELAPSED)))
        tick(flow, Duration.ofSeconds(30))
        assertNull(checks.last().problem)
        assertTrue(Statements.CHECK_INCOMING in cloud.executed)
    }

    /** Cada revisión lleva la época de la pantalla de cuando empezó (`_handoffEpoch`). */
    @Test
    fun laRevisionLlevaLaEpocaDeCuandoEmpezo() {
        val flow = linked()
        flow.setHandoffContext(inPatients = true, outgoingId = null)
        flow.setHandoffEpoch(7)
        tick(flow, Duration.ofSeconds(1))
        assertEquals(7L, checks.single().epoch)
    }

    /** `HasPendingSend`: un envío pendiente en cualquier fase acelera la revisión, aunque solo del confirmado se pregunta el estado. */
    @Test
    fun unEnvioSinConfirmarTambienAceleraLaRevision() {
        val flow = linked()
        // Sin compañeros: la lista dice que no hay ninguno
        flow.setHandoffContext(inPatients = true, outgoingId = null)
        tick(flow, Duration.ofSeconds(1))
        assertEquals(0, checks.single().result!!.peerCount)
        tick(flow, Duration.ofSeconds(5))
        assertEquals("Sin compañero ni envío, cada 30 s", 1, checks.size)
        flow.setHandoffContext(inPatients = true, outgoingId = null, pendingSend = true)
        tick(flow, Duration.ofSeconds(25))
        assertEquals(2, checks.size)
        tick(flow, Duration.ofSeconds(4))
        assertEquals(3, checks.size)
        assertFalse("Del envío sin confirmar no se pregunta el estado", checks.last().result!!.hadOutgoing)
    }
}
