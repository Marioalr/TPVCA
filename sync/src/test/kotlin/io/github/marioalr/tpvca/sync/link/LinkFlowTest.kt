package io.github.marioalr.tpvca.sync.link

import io.github.marioalr.tpvca.domain.devices.DeviceRole
import io.github.marioalr.tpvca.sync.FakeCloud
import io.github.marioalr.tpvca.sync.InMemorySyncStore
import io.github.marioalr.tpvca.sync.MutableClock
import io.github.marioalr.tpvca.sync.Statements
import io.github.marioalr.tpvca.sync.SyncContract
import io.github.marioalr.tpvca.sync.engine.SyncEngine
import io.github.marioalr.tpvca.sync.engine.SyncStore
import io.github.marioalr.tpvca.sync.hrana.HranaConnection
import io.github.marioalr.tpvca.sync.hrana.HranaException
import io.github.marioalr.tpvca.sync.hrana.HranaValue
import io.github.marioalr.tpvca.sync.hrana.text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** La vinculación contra una PC simulada: la PC «aprueba», «rechaza» y «quita» escribiendo la fila de `DeviceRegistry` que escribe `TursoSyncService`. */
class LinkFlowTest {
    private val clock = MutableClock(Instant.parse("2026-11-03T18:00:00Z"))
    private val cloud = FakeCloud().apply { registry = null }

    private class MemoryVault : CredentialVault {
        var stored: StoredCredential = StoredCredential.Empty

        override fun load() = stored

        override fun save(credential: DeviceCredential) {
            stored = StoredCredential.Present(DeviceCredential.fromJson(credential.toJson())!!)
        }

        override fun clear() {
            stored = StoredCredential.Empty
        }

        val credential get() = (stored as? StoredCredential.Present)?.credential
    }

    private class MemoryDatabase : LocalDatabase {
        var current = InMemorySyncStore()
        var erased = 0
        var opened = 0

        override fun store(): SyncStore {
            opened++
            return current
        }

        override fun erase() {
            erased++
            current = InMemorySyncStore()
        }
    }

    private class MemoryNotices : LinkNotices {
        var value: Revocation? = null

        override fun revocation() = value

        override fun saveRevocation(reason: String?) {
            value = Revocation(reason)
        }

        override fun clearRevocation() {
            value = null
        }
    }

    private val vault = MemoryVault()
    private val database = MemoryDatabase()
    private val notices = MemoryNotices()
    private var secure = true
    private var filesErased = 0
    private var ids = 0
    private val published = ArrayList<LinkState>()
    private var connected = ArrayList<Pair<String, String>>()
    private val store get() = database.current

    private val url = "https://consultorio-ejemplo.turso.io"
    private val token = "token-de-prueba"

    /** Un proceso nuevo de la app sobre lo mismo que quedó guardado. */
    private fun flow() = LinkFlow(
        vault,
        database,
        notices,
        { secure },
        "0.1.0",
        "Galaxy Tab S9",
        { filesErased++ },
        connect = { u, t ->
            connected += u to t
            cloud.connection as HranaConnection
        },
        clock = clock,
        newId = { "equipo${++ids}abc3f9a2c" },
        newNonce = { "nonce-${++ids}" },
        onState = { published += it },
    )

    private fun tablet(): Pair<LinkFlow, LinkState> = flow().let { it to it.link(url, token, "Consultorio Peña", DeviceRole.AndroidTablet) }

    private fun approve(role: String = "AndroidTablet", code: String = "T1") {
        cloud.registry = listOf(HranaValue.Text(code), HranaValue.Text(role), HranaValue.Null, HranaValue.Null)
    }

    private fun revoke(reason: String, code: String = "T1", role: String = "AndroidTablet") {
        cloud.registry = listOf(HranaValue.Text(code), HranaValue.Text(role), HranaValue.Text("2026-11-03 18:00:00.000"), HranaValue.Text(reason))
    }

    /** Vinculado, aprobado y con la primera descarga hecha. */
    private fun linked(): LinkFlow {
        approve()
        val (flow, state) = tablet()
        assertTrue("$state", state is LinkState.Linked)
        cloud.requests.clear()
        return flow
    }

    private fun rejectToken() {
        cloud.onStatement = { throw HranaException.fromHttp(401, """{"error":"Unauthorized: invalid JWT token"}""") }
    }

    private fun malformedToken() {
        cloud.onStatement = { throw HranaException.fromHttp(400, """{"error":"JWT error: InvalidToken"}""") }
    }

    private fun acceptToken() {
        cloud.onStatement = {}
    }

    // ----------------------------------------------------------------------------------------------------------------- vincular

    @Test
    fun sinBloqueoDePantallaSeNiegaSinGuardarNiConectar() {
        secure = false
        val state = flow().link(url, token, "", DeviceRole.AndroidTablet)
        assertEquals(LinkState.NotLinked(LinkProblem.NoSecureLock), state)
        assertEquals(StoredCredential.Empty, vault.stored)
        assertTrue(cloud.requests.isEmpty())
    }

    @Test
    fun unaUrlSinCifrarOUnTokenVacioNoSeGuardan() {
        assertTrue((flow().link("http://192.168.1.10:8080", token, "", DeviceRole.AndroidTablet) as LinkState.NotLinked).problem is LinkProblem.BadUrl)
        assertEquals(LinkState.NotLinked(LinkProblem.EmptyToken), flow().link(url, "  \n", "", DeviceRole.AndroidTablet))
        assertEquals(StoredCredential.Empty, vault.stored)
        assertTrue(cloud.requests.isEmpty())
    }

    @Test
    fun alVincularEscribeSuLatidoYEsperaSinBajarNada() {
        val (_, state) = tablet()
        assertEquals(LinkState.WaitingApproval("3F9A2C", DeviceRole.AndroidTablet, "Consultorio Peña"), state)
        assertEquals(token, vault.credential!!.token)
        // Primero revisa la nube; el latido va hasta saber que es la que debe ser
        assertEquals(
            listOf(Statements.META_ALL, Statements.REGISTRY_OWN, Statements.HEARTBEAT_READ_NONCE, Statements.HEARTBEAT_WRITE),
            cloud.requests.flatten(),
        )
        assertEquals(0, store.rowCounts().values.sum())
    }

    @Test
    fun elLatidoDiceLoQuePideElContrato() {
        flow().link(url, token, "", DeviceRole.AndroidMobileAdmin)
        val args = cloud.heartbeats.single()
        assertEquals(
            listOf(vault.credential!!.deviceId, "Galaxy Tab S9", "AndroidMobileAdmin", "0.1.0", "Android"),
            args.take(5).map { it.text },
        )
        assertEquals(SyncContract.CLIENT_PROTOCOL.toString(), args[5].text)
        assertEquals("No recibe consultas hasta K8", "0", args[6].text)
        assertEquals(SyncContract.REVISION.toString(), args[7].text)
        assertEquals("0", args[8].text)
        assertEquals(HranaValue.Null, args[9])
        assertEquals(cloud.rowNonce, args[10].text)
    }

    @Test
    fun conLaNubeSinVerificarNoEscribeNada() {
        cloud.meta.remove("clinicId")
        val (_, state) = tablet()
        assertTrue("$state", state is LinkState.CloudProblem)
        assertTrue(cloud.heartbeats.isEmpty())
        assertFalse(Statements.HEARTBEAT_WRITE in cloud.requests.flatten())
    }

    @Test
    fun otraVersionDeLaNubeNoSeBaja() {
        cloud.meta["minClientProtocol"] = "99"
        assertTrue(tablet().second is LinkState.CloudProblem)
        assertTrue(cloud.heartbeats.isEmpty())
    }

    @Test
    fun mientrasEsperaSoloLeeSuFilaYLaCadenciaSeRespeta() {
        val (flow, _) = tablet()
        cloud.requests.clear()
        clock.advance(Duration.ofSeconds(5))
        flow.refresh(Refresh.TICK)
        assertTrue("Todavía no toca", cloud.requests.isEmpty())

        clock.advance(Duration.ofSeconds(5))
        flow.refresh(Refresh.TICK)
        assertEquals(listOf(listOf(Statements.REGISTRY_OWN)), cloud.requests)

        cloud.requests.clear()
        clock.advance(Duration.ofSeconds(50))
        flow.refresh(Refresh.TICK)
        assertEquals("Cada 60 s, su latido", listOf(Statements.HEARTBEAT_READ_NONCE, Statements.HEARTBEAT_WRITE, Statements.REGISTRY_OWN), cloud.requests.flatten())
    }

    @Test
    fun sinRedSigueEsperandoYLoDice() {
        val (flow, _) = tablet()
        cloud.onStatement = { throw HranaException(HranaException.NETWORK_ERROR, "No se pudo conectar con Turso.") }
        val state = flow.refresh(Refresh.USER) as LinkState.WaitingApproval
        assertEquals("No se pudo conectar con Turso.", state.problem)
        acceptToken()
        assertNull((flow.refresh(Refresh.USER) as LinkState.WaitingApproval).problem)
    }

    @Test
    fun sinRedAlVincularNoSeDaPorVinculado() {
        cloud.onStatement = { throw HranaException(HranaException.NETWORK_ERROR, "No se pudo conectar con Turso.") }
        val (flow, state) = tablet()
        assertEquals(LinkState.WaitingApproval("3F9A2C", DeviceRole.AndroidTablet, "Consultorio Peña", "No se pudo conectar con Turso."), state)
        assertTrue("Después de cerrar la app tampoco", flow().restore() is LinkState.WaitingApproval)
        // Vuelve la red: primero escribe su latido, que todavía no había podido
        acceptToken()
        assertTrue(flow.refresh(Refresh.USER) is LinkState.WaitingApproval)
        assertEquals(1, cloud.heartbeats.size)
    }

    @Test
    fun sinRedDespuesDeAprobadoSigueVinculadoYRetomaLaDescarga() {
        val (flow, _) = tablet()
        approve()
        cloud.add("Categories", "c1", "2026-11-03 10:00:00.0000000", "2026-11-03 10:00:00.000")
        cloud.onStatement = { if (it.sql == Statements.HANDSHAKE) throw HranaException(HranaException.NETWORK_ERROR, "Sin red.") }
        val cut = flow.refresh(Refresh.USER) as LinkState.Linked
        assertEquals("Sin red.", cut.notice)
        assertTrue("Después de cerrar la app sigue vinculado", flow().restore() is LinkState.Linked)
        acceptToken()
        assertNull((flow.refresh(Refresh.USER) as LinkState.Linked).notice)
        assertEquals(setOf("c1"), store.rows("Categories").keys)
    }

    @Test
    fun alAprobarBajaYQuedaVinculado() {
        val (flow, _) = tablet()
        cloud.add("Categories", "c1", "2026-11-03 10:00:00.0000000", "2026-11-03 10:00:00.000")
        approve()
        published.clear()
        val state = flow.refresh(Refresh.USER)
        assertEquals(LinkState.Linked(DeviceRole.AndroidTablet, "T1", "Consultorio Peña"), state)
        assertTrue("Se ve mientras baja: $published", published.any { it is LinkState.Downloading })
        assertEquals(setOf("c1"), store.rows("Categories").keys)
        assertEquals("El consultorio queda en la credencial", "clinica-1", vault.credential!!.clinicId)
    }

    @Test
    fun alReabrirSigueEnDondeIba() {
        tablet()
        assertTrue(flow().restore() is LinkState.WaitingApproval)
        approve()
        flow().also { it.restore() }.refresh(Refresh.OPENED).let { assertTrue("$it", it is LinkState.Linked) }
        val deviceId = vault.credential!!.deviceId
        val reopened = flow()
        assertEquals(LinkState.Linked(DeviceRole.AndroidTablet, "T1", "Consultorio Peña"), reopened.restore())
        assertEquals(deviceId, vault.credential!!.deviceId)
        assertEquals(DeviceRole.AndroidTablet, reopened.role)
    }

    @Test
    fun abrirLaAppNoTocaLaRed() {
        linked()
        flow().restore()
        assertTrue(cloud.requests.isEmpty())
    }

    @Test
    fun otraInstalacionConSuDeviceIdTomaUnoNuevoYEsperaOtraVez() {
        val flow = linked()
        val first = vault.credential!!.deviceId
        store.put("Categories", "c", "UpdatedAtUtc" to "2026-11-03 10:00:00.0000000")
        // Otra copia escribe su nonce en la misma fila
        cloud.rowNonce = "de-otra-instalacion"
        cloud.registry = null
        clock.advance(Duration.ofSeconds(120))
        val state = flow.refresh(Refresh.OPENED)
        assertTrue("$state", state is LinkState.WaitingApproval && state.newIdentity)
        assertNotEquals(first, vault.credential!!.deviceId)
        assertTrue("Vuelve a bajar todo", store.rows("Categories").isEmpty())
    }

    @Test
    fun elPrimerLatidoDeCadaArranqueNoRevisaElNonce() {
        linked()
        val id = vault.credential!!.deviceId
        // La fila trae el nonce del arranque anterior
        val state = flow().also { it.restore() }.refresh(Refresh.OPENED)
        assertTrue("$state", state is LinkState.Linked)
        assertEquals(id, vault.credential!!.deviceId)
    }

    @Test
    fun laCadenciaDelCelularEsCada120SConLatido() {
        approve("AndroidMobileAdmin", "C1")
        val flow = flow()
        assertTrue(flow.link(url, token, "", DeviceRole.AndroidMobileAdmin) is LinkState.Linked)
        val beats = cloud.heartbeats.size
        cloud.requests.clear()
        clock.advance(Duration.ofSeconds(119))
        flow.refresh(Refresh.TICK)
        assertTrue(cloud.requests.isEmpty())
        clock.advance(Duration.ofSeconds(1))
        flow.refresh(Refresh.TICK)
        assertEquals(beats + 1, cloud.heartbeats.size)
        assertTrue(Statements.REGISTRY_OWN in cloud.requests.flatten())
    }

    @Test
    fun laTabletEscribeSuLatidoCada60SyBajaCada10Min() {
        val flow = linked()
        val beats = cloud.heartbeats.size
        clock.advance(Duration.ofSeconds(60))
        flow.refresh(Refresh.TICK)
        assertEquals(beats + 1, cloud.heartbeats.size)
        assertFalse("Sin bajar todavía", Statements.REGISTRY_OWN in cloud.requests.flatten())
        clock.advance(Duration.ofMinutes(10))
        flow.refresh(Refresh.TICK)
        assertTrue(Statements.REGISTRY_OWN in cloud.requests.flatten())
    }

    // ----------------------------------------------------------------------------------------------------------------- rol

    @Test
    fun aprobadoConElOtroRolNoBajaNiBorra() {
        val (flow, _) = tablet()
        approve("AndroidMobileAdmin", "C1")
        val state = flow.refresh(Refresh.USER)
        assertEquals(LinkState.WrongRole("AndroidMobileAdmin", DeviceRole.AndroidTablet), state)
        assertEquals(0, database.erased)
        assertNotNull(vault.credential)
        assertNull(store.meta(SyncEngine.FIRST_DOWNLOAD_DONE))
        assertFalse(cloud.requests.flatten().any { it.startsWith("SELECT * FROM \"Categories\"") })
    }

    @Test
    fun unaFilaDeTabletEnUnCelularYaVinculadoConservaLoQueBajo() {
        approve("AndroidMobileAdmin", "C1")
        val flow = flow()
        flow.link(url, token, "", DeviceRole.AndroidMobileAdmin)
        store.put("Categories", "c", "UpdatedAtUtc" to "2026-11-03 10:00:00.0000000")
        approve("AndroidTablet", "T1")
        assertEquals(LinkState.WrongRole("AndroidTablet", DeviceRole.AndroidMobileAdmin), flow.refresh(Refresh.USER))
        assertEquals(setOf("c"), store.rows("Categories").keys)
        assertEquals(0, database.erased)
    }

    @Test
    fun unaFilaDePcTampocoBaja() {
        val (flow, _) = tablet()
        approve("WindowsDesktop", "P1")
        assertEquals(LinkState.WrongRole("WindowsDesktop", DeviceRole.AndroidTablet), flow.refresh(Refresh.USER))
    }

    // ----------------------------------------------------------------------------------------------------------------- revocación

    @Test
    fun rechazado_noBajaNadaBorraTodoYPideOtroDeviceId() {
        val (flow, _) = tablet()
        val rejectedId = vault.credential!!.deviceId
        revoke("rechazado", code = "X1")
        assertEquals(LinkState.Revoked("rechazado", erased = true), flow.refresh(Refresh.USER))
        assertEquals(StoredCredential.Empty, vault.stored)
        assertEquals(1, database.erased)
        assertEquals(1, filesErased)
        assertEquals(LinkState.NotLinked(), flow.acknowledgeRevocation())

        cloud.registry = null
        flow.link(url, token, "", DeviceRole.AndroidTablet)
        assertNotEquals("Su fila rechazada no se borra nunca: entra con otro", rejectedId, vault.credential!!.deviceId)
    }

    @Test
    fun quitado_sinNadaPendienteBorraBaseCredencialYBorradores() {
        val flow = linked()
        store.put("Categories", "c", "UpdatedAtUtc" to "2026-11-03 10:00:00.0000000")
        revoke("quitado")
        assertEquals(LinkState.Revoked("quitado", erased = true), flow.refresh(Refresh.USER))
        assertEquals(StoredCredential.Empty, vault.stored)
        assertEquals(1, database.erased)
        assertEquals(1, filesErased)
        assertNull(flow.role)
    }

    @Test
    fun quitado_conAlgoPendienteConservaTodo() {
        val flow = linked()
        store.pending = 3
        revoke("quitado")
        assertEquals(LinkState.Revoked("quitado", erased = false, pending = 3), flow.refresh(Refresh.USER))
        assertNotNull(vault.credential)
        assertEquals(0, database.erased)
        assertEquals(LinkState.Revoked("quitado", erased = false, pending = 3), flow.acknowledgeRevocation())
    }

    @Test
    fun perdido_borraSinSubirAunqueHayaAlgoPendiente() {
        val flow = linked()
        store.pending = 3
        revoke("perdido")
        assertEquals(LinkState.Revoked("perdido", erased = true), flow.refresh(Refresh.USER))
        assertEquals(StoredCredential.Empty, vault.stored)
        assertEquals(1, database.erased)
    }

    @Test
    fun revocadoLoDiceTambienDespuesDeCerrarLaApp() {
        val flow = linked()
        revoke("perdido")
        flow.refresh(Refresh.USER)
        assertEquals(LinkState.Revoked("perdido", erased = true), flow().restore())
        assertTrue("Sin credencial no intenta nada", flow().refresh(Refresh.USER) is LinkState.NotLinked)
    }

    @Test
    fun unBorradoQueSeCortoSeTerminaAlAbrir() {
        linked()
        // El aviso se guarda antes de empezar a borrar: la app se cerró con la credencial todavía ahí
        notices.saveRevocation("quitado")
        assertEquals(LinkState.Revoked("quitado", erased = true), flow().restore())
        assertEquals(StoredCredential.Empty, vault.stored)
        assertEquals(1, database.erased)
    }

    // ----------------------------------------------------------------------------------------------------------------- 401

    @Test
    fun unPrimer401NoBorraNadaYLoAutomaticoDejaDeIntentar() {
        val flow = linked()
        store.put("Categories", "c", "UpdatedAtUtc" to "2026-11-03 10:00:00.0000000")
        rejectToken()
        assertEquals(LinkState.NeedsToken(malformed = false, erased = false, pending = 0), flow.refresh(Refresh.OPENED))
        assertEquals(token, vault.credential!!.token)
        assertEquals(0, database.erased)
        assertEquals(setOf("c"), store.rows("Categories").keys)

        cloud.requests.clear()
        clock.advance(Duration.ofMinutes(9))
        flow.refresh(Refresh.OPENED)
        assertTrue("Lo automático no llega a Turso", cloud.requests.isEmpty())
    }

    @Test
    fun un401PasajeroNoDejaHuella() {
        val flow = linked()
        store.put("Categories", "c", "UpdatedAtUtc" to "2026-11-03 10:00:00.0000000")
        rejectToken()
        flow.refresh(Refresh.OPENED)
        acceptToken()
        clock.advance(Duration.ofMinutes(10))
        val state = flow.refresh(Refresh.OPENED)
        assertTrue("$state", state is LinkState.Linked)
        assertEquals(0, database.erased)
        assertEquals(setOf("c"), store.rows("Categories").keys)
        assertNull(store.meta(SyncEngine.AUTH_FIRST_401))
        // Y el siguiente 401 vuelve a contar desde cero: no se borra por este
        rejectToken()
        clock.advance(Duration.ofHours(2))
        assertEquals(LinkState.NeedsToken(malformed = false, erased = false, pending = 0), flow.refresh(Refresh.OPENED))
        assertEquals(0, database.erased)
    }

    @Test
    fun dos401Con10MinYNadaPendienteBorranLaBaseYElTokenPeroNoElDeviceId() {
        val flow = linked()
        val id = vault.credential!!.deviceId
        store.put("Categories", "c", "UpdatedAtUtc" to "2026-11-03 10:00:00.0000000")
        rejectToken()
        flow.refresh(Refresh.OPENED)
        clock.advance(Duration.ofMinutes(10))
        assertEquals(LinkState.NeedsToken(malformed = false, erased = true, pending = 0), flow.refresh(Refresh.OPENED))
        assertEquals(1, database.erased)
        assertNull(vault.credential!!.token)
        assertEquals(id, vault.credential!!.deviceId)
        assertEquals(url, vault.credential!!.url)
        assertEquals("No toca los archivos del equipo: no es una revocación", 0, filesErased)

        // También después de cerrar la app, y sin abrir una base que ya no hace falta
        val opened = database.opened
        val reopened = flow()
        assertEquals(LinkState.NeedsToken(malformed = false, erased = true, pending = 0), reopened.restore())
        assertEquals(opened, database.opened)
        cloud.requests.clear()
        reopened.refresh(Refresh.USER)
        assertTrue("Sin token no hay a qué conectarse", cloud.requests.isEmpty())
    }

    @Test
    fun conElTokenNuevoBajaTodoSinAprobarOtraVez() {
        val flow = linked()
        val id = vault.credential!!.deviceId
        rejectToken()
        flow.refresh(Refresh.OPENED)
        clock.advance(Duration.ofMinutes(10))
        flow.refresh(Refresh.OPENED)

        acceptToken()
        cloud.add("Categories", "de-vuelta", "2026-11-03 10:00:00.0000000", "2026-11-03 10:00:00.000")
        val state = flow.replaceToken("token-nuevo-de-prueba")
        assertTrue("$state", state is LinkState.Linked)
        assertEquals(id, vault.credential!!.deviceId)
        assertEquals("token-nuevo-de-prueba", vault.credential!!.token)
        assertEquals(setOf("de-vuelta"), store.rows("Categories").keys)
        assertEquals(url to "token-nuevo-de-prueba", connected.last())
    }

    @Test
    fun con401YAlgoPendienteNuncaBorraYConserva() {
        val flow = linked()
        store.pending = 2
        rejectToken()
        assertEquals(LinkState.NeedsToken(malformed = false, erased = false, pending = 2), flow.refresh(Refresh.OPENED))
        repeat(3) {
            clock.advance(Duration.ofHours(6))
            assertEquals(LinkState.NeedsToken(malformed = false, erased = false, pending = 2), flow.refresh(Refresh.USER))
        }
        assertEquals(0, database.erased)
        assertEquals(token, vault.credential!!.token)

        acceptToken()
        val state = flow.replaceToken("token-nuevo-de-prueba")
        assertTrue("$state", state is LinkState.Linked)
        assertEquals("Lo pendiente sigue", 2, store.pending)
    }

    @Test
    fun unTokenMalCopiadoNoCuentaParaBorrarse() {
        val flow = linked()
        malformedToken()
        assertEquals(LinkState.NeedsToken(malformed = true, erased = false, pending = 0), flow.refresh(Refresh.OPENED))
        repeat(3) {
            clock.advance(Duration.ofHours(1))
            assertEquals(LinkState.NeedsToken(malformed = true, erased = false, pending = 0), flow.refresh(Refresh.USER))
        }
        assertEquals(0, database.erased)
        assertEquals(token, vault.credential!!.token)
    }

    @Test
    fun alVincularConUnTokenMalCopiadoLoPideOtraVezConElMismoEquipo() {
        malformedToken()
        val (flow, state) = tablet()
        assertEquals(LinkState.NeedsToken(malformed = true, erased = false, pending = 0), state)
        val id = vault.credential!!.deviceId
        acceptToken()
        assertTrue(flow.replaceToken("token-bueno-de-prueba") is LinkState.WaitingApproval)
        assertEquals(id, vault.credential!!.deviceId)
    }

    @Test
    fun el401MientrasEsperaPasaPorLasMismasReglas() {
        val (flow, _) = tablet()
        rejectToken()
        assertEquals(LinkState.NeedsToken(malformed = false, erased = false, pending = 0), flow.refresh(Refresh.USER))
        assertEquals(0, database.erased)
    }

    @Test
    fun unTokenNuevoQueNoSirveNoCambiaLoGuardado() {
        val flow = linked()
        rejectToken()
        flow.refresh(Refresh.OPENED)
        val state = flow.replaceToken("   ")
        assertEquals(LinkProblem.EmptyToken, (state as LinkState.NeedsToken).problem)
        assertEquals(token, vault.credential!!.token)
    }

    @Test
    fun elTokenNuevoDeOtraNubeNoBajaNiBorra() {
        val flow = linked()
        store.put("Categories", "c", "UpdatedAtUtc" to "2026-11-03 10:00:00.0000000")
        rejectToken()
        flow.refresh(Refresh.OPENED)
        acceptToken()
        cloud.meta["clinicId"] = "otro-consultorio"
        val state = flow.replaceToken("token-de-otra-base", "https://otra-base-ejemplo.example.com")
        assertTrue("$state", state is LinkState.CloudProblem)
        assertEquals(setOf("c"), store.rows("Categories").keys)
        assertEquals(0, database.erased)
    }

    @Test
    fun unaRevocacionGanaAlTokenRechazadoCuandoSeVuelveAConectar() {
        val flow = linked()
        rejectToken()
        flow.refresh(Refresh.OPENED)
        acceptToken()
        revoke("perdido")
        assertEquals(LinkState.Revoked("perdido", erased = true), flow.replaceToken("token-nuevo-de-prueba"))
        assertEquals(StoredCredential.Empty, vault.stored)
    }

    // ----------------------------------------------------------------------------------------------------------------- lo demás

    @Test
    fun unaCredencialQueYaNoSeAbreBorraTodoYPideVincular() {
        linked()
        vault.stored = StoredCredential.Unreadable
        assertEquals(LinkState.NotLinked(LinkProblem.CredentialLost), flow().restore())
        assertEquals(1, database.erased)
        assertEquals(StoredCredential.Empty, vault.stored)
    }

    @Test
    fun cancelarBorraLaCredencial() {
        val (flow, _) = tablet()
        assertEquals(LinkState.NotLinked(), flow.forget())
        assertEquals(StoredCredential.Empty, vault.stored)
        assertNull(flow.role)
    }

    @Test
    fun cancelarConAlgoPendienteNoHaceNada() {
        val flow = linked()
        store.pending = 1
        val before = flow.state
        assertEquals(before, flow.forget())
        assertNotNull(vault.credential)
        assertEquals(0, database.erased)
    }

    @Test
    fun laQuotaOLaRedNoLoSacanDeVinculado() {
        val flow = linked()
        cloud.failSnapshot = { HranaException(HranaException.NETWORK_ERROR, "No se pudo conectar con Turso.") }
        cloud.onStatement = {}
        val state = flow.refresh(Refresh.USER) as LinkState.Linked
        assertEquals("No se pudo conectar con Turso.", state.notice)
        assertEquals(0, database.erased)
    }

    @Test
    fun ningunEstadoNiLaCredencialComoTextoLlevanElToken() {
        val flow = linked()
        val credential = vault.credential!!
        rejectToken()
        flow.refresh(Refresh.OPENED)
        for (text in published.map { it.toString() } + credential.toString() + store.metaValues.toString()) {
            assertFalse(text, text.contains(token))
            assertFalse(text, text.contains(url))
        }
    }

    @Test
    fun laCredencialSobreviveAlJsonSinPerderNada() {
        val credential = DeviceCredential(url, token, "Peña", "id", DeviceRole.AndroidMobileAdmin, "clinica")
        val back = DeviceCredential.fromJson(credential.toJson())!!
        assertEquals(listOf(url, token, "Peña", "id", DeviceRole.AndroidMobileAdmin, "clinica"), listOf(back.url, back.token, back.clinicName, back.deviceId, back.requestedRole, back.clinicId))
        assertNull(DeviceCredential.fromJson(credential.copy(token = null).toJson())!!.token)
        assertNull(DeviceCredential.fromJson("no es json"))
        assertNull(DeviceCredential.fromJson("""{"url":"x"}"""))
    }

    @Test
    fun losSeisCaracteresSonLosDeLaPc() {
        assertEquals("3F9A2C", Heartbeat.suffix("android-tab-3f9a2c"))
        assertEquals("ABC", Heartbeat.suffix("abc"))
    }
}
