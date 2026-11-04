package io.github.marioalr.tpvca.sync

import io.github.marioalr.tpvca.domain.devices.DeviceRole
import io.github.marioalr.tpvca.sync.engine.ParkDirection
import io.github.marioalr.tpvca.sync.engine.SaleLine
import io.github.marioalr.tpvca.sync.engine.StoreChanges
import io.github.marioalr.tpvca.sync.engine.SyncEngine
import io.github.marioalr.tpvca.sync.engine.SyncEngineConfig
import io.github.marioalr.tpvca.sync.engine.SyncOutcome
import io.github.marioalr.tpvca.sync.engine.SyncTrigger
import io.github.marioalr.tpvca.sync.engine.WriteEngine
import io.github.marioalr.tpvca.sync.engine.WriteRefused
import io.github.marioalr.tpvca.sync.engine.unsyncedChanges
import io.github.marioalr.tpvca.sync.link.MemoryFolioNumbers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.math.BigDecimal

/** La frontera de escritura, la cola y lo que nunca se pierde, contra una nube que ejecuta el SQL de verdad. */
class WriteEngineTest {
    private val cloud = SqlCloud()
    private val store = InMemorySyncStore()
    private val folios = MemoryFolioNumbers()

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

    private fun approve(role: DeviceRole, code: String = "T1") {
        cloud.admin("INSERT INTO DeviceRegistry (DeviceId, DeviceCode, Role, DisplayName, Model, PairedAtUtc) VALUES ('device-b', ?, ?, 'b', '', 'x')", code, role.name)
    }

    private fun revoke(reason: String) = cloud.admin("UPDATE DeviceRegistry SET RevokedAtUtc = 'x', RevokedReason = ? WHERE DeviceId = 'device-b'", reason)

    private val writes = WriteEngine(store, { "device-b" }, folios, { cloud.connection })

    private fun engine(guard: io.github.marioalr.tpvca.sync.engine.EraseGuard = io.github.marioalr.tpvca.sync.engine.EraseGuard.NONE, config: SyncEngineConfig = SyncEngineConfig("device-b")) =
        SyncEngine(cloud.connection, store, config, eraseGuard = guard)

    private fun linked(role: DeviceRole = DeviceRole.AndroidTablet, code: String = "T1") {
        approve(role, code)
        assertTrue(engine().sync(SyncTrigger.USER) is SyncOutcome.Completed)
        assertEquals(role.name, store.meta(SyncEngine.DEVICE_ROLE))
    }

    private val patient = "10000000-0000-4000-8000-000000000001"
    private val product = "10000000-0000-4000-8000-000000000002"

    private fun appointment() = mapOf("PatientRecordSyncId" to patient, "Date" to "2030-01-10 00:00:00.0000000", "Time" to "10:00", "Status" to "Programada", "Reason" to "Revisión")

    private fun cloudPatient() {
        cloud.admin("INSERT INTO Patients (SyncId, UpdatedAtUtc, SyncedAtUtc, OriginDeviceId, FullName, RecordNumber) VALUES (?, '2026-01-01 00:00:00.0000000', ?, 'pc', 'Ana', 'EXP-2026-001')", patient, cloud.nowText())
        cloud.admin("INSERT INTO Categories (SyncId, UpdatedAtUtc, SyncedAtUtc, Name) VALUES ('10000000-0000-4000-8000-000000000003', '2026-01-01 00:00:00.0000000', ?, 'C')", cloud.nowText())
        cloud.admin(
            "INSERT INTO Products (SyncId, UpdatedAtUtc, SyncedAtUtc, Name, Code, Price, CurrentStock, IsActive, CategorySyncId) VALUES (?, '2026-01-01 00:00:00.0000000', ?, 'P', 'P1', '10', 1, 1, '10000000-0000-4000-8000-000000000003')",
            product, cloud.nowText(),
        )
    }

    private fun refused(reason: WriteRefused.Reason, block: () -> Unit) = assertEquals(reason, assertThrows(WriteRefused::class.java) { block() }.reason)

    @Test
    fun sinAprobarNoSeEscribeNada() {
        refused(WriteRefused.Reason.NOT_APPROVED) { writes.create("Appointments", appointment()) }
        assertEquals(0, store.pendingChanges())
    }

    @Test
    fun elCelularNoAnotaExpedientesNotasRecetasNiVentas() {
        cloudPatient()
        linked(DeviceRole.AndroidMobileAdmin, "C1")
        val before = store.rowCounts()
        refused(WriteRefused.Reason.FORBIDDEN) { writes.create("Patients", mapOf("FullName" to "X")) }
        refused(WriteRefused.Reason.FORBIDDEN) { writes.edit("Patients", patient, mapOf("Phone" to "1")) }
        refused(WriteRefused.Reason.FORBIDDEN) { writes.editSheet(patient, "ActivePrescriptionJson") { emptyList() } }
        refused(WriteRefused.Reason.FORBIDDEN) { writes.newPatient("X") }
        refused(WriteRefused.Reason.FORBIDDEN) { writes.create("PatientConsultations", mapOf("PatientRecordSyncId" to patient)) }
        refused(WriteRefused.Reason.FORBIDDEN) { writes.create("LabResults", mapOf("PatientRecordSyncId" to patient)) }
        refused(WriteRefused.Reason.FORBIDDEN) { writes.registerSale(listOf(SaleLine(productSyncId = product, quantity = 1, unitPrice = BigDecimal.TEN))) }
        refused(WriteRefused.Reason.FORBIDDEN) { writes.cancelSale("TKT") }
        refused(WriteRefused.Reason.FORBIDDEN) { writes.edit("Products", product, mapOf("Price" to "1")) }
        refused(WriteRefused.Reason.FORBIDDEN) { writes.closeBatch("x") }
        refused(WriteRefused.Reason.FORBIDDEN) { writes.delete("Patients", patient) }
        // Un movimiento que no es una entrada de compra, tampoco
        refused(WriteRefused.Reason.FORBIDDEN) { writes.create("InventoryMovements", mapOf("ProductSyncId" to product, "MovementType" to "6")) }
        assertEquals(0, store.pendingChanges())
        assertEquals(before, store.rowCounts())
    }

    @Test
    fun elCelularSiAgendaYRegistraEntradasDeMercancia() {
        cloudPatient()
        linked(DeviceRole.AndroidMobileAdmin, "C1")
        writes.create("Appointments", appointment())
        val batch = writes.registerStockEntry(product, "L-1", null, 5, BigDecimal("3.5"), "Pedido LUIN #1", "Dr.")
        assertEquals(3, store.pendingChanges())
        // Regla «Catálogo con varios equipos»: el celular nunca escribe Products
        assertTrue(store.outbox().none { it.entityType == "Product" })
        assertTrue(engine().sync(SyncTrigger.USER) is SyncOutcome.Completed)
        assertEquals(0, store.pendingChanges())
        assertEquals("L-1", cloud.admin("SELECT BatchNumber FROM ProductBatches WHERE SyncId = ?", batch).single()["BatchNumber"]?.text)
        assertEquals("1", cloud.admin("SELECT CurrentStock FROM Products WHERE SyncId = ?", product).single()["CurrentStock"]?.text)
    }

    @Test
    fun laColaYLaEscrituraVanJuntasOSeDeshacenJuntas() {
        cloudPatient()
        linked()
        assertThrows(IllegalStateException::class.java) {
            store.transaction {
                writes.writer.create("Appointments", "10000000-0000-4000-8000-000000000009", appointment())
                error("Se cae a media operación")
            }
        }
        assertEquals(0, store.pendingChanges())
        assertTrue(store.rows("Appointments").isEmpty())
    }

    @Test
    fun loQueSeEditaDuranteLaSubidaSeQuedaEnLaCola() {
        cloudPatient()
        linked()
        val id = writes.create("Appointments", appointment())
        val beforePush = store.outbox().single().seq
        writes.edit("Appointments", id, mapOf("Reason" to "Otra"))
        assertTrue(store.outbox().single().seq > beforePush)
        store.apply(StoreChanges(outboxClearedUpTo = beforePush))
        assertEquals(1, store.pendingChanges())
    }

    @Test
    fun unCorteNoPierdeNadaYSeVuelveAMandar() {
        cloudPatient()
        linked()
        val id = writes.create("Appointments", appointment())
        cloud.armCut(SqlCloud.Cut(1, SqlCloud.Cut.Mode.BEFORE_APPLY))
        assertTrue(engine().sync(SyncTrigger.USER) is SyncOutcome.Failed)
        assertEquals(1, store.pendingChanges())
        cloud.armCut(SqlCloud.Cut(1, SqlCloud.Cut.Mode.LOST_RESPONSE))
        assertTrue(engine().sync(SyncTrigger.USER) is SyncOutcome.Failed)
        assertEquals(1, store.pendingChanges())
        cloud.armCut(null)
        assertTrue(engine().sync(SyncTrigger.USER) is SyncOutcome.Completed)
        assertEquals(0, store.pendingChanges())
        assertEquals(1, cloud.admin("SELECT SyncId FROM Appointments WHERE SyncId = ?", id).size)
    }

    @Test
    fun soloLecturaNoEscribeYConservaLaCola() {
        cloudPatient()
        linked()
        val id = writes.create("Appointments", appointment())
        store.apply(StoreChanges(meta = mapOf(SyncEngine.READ_ONLY to "otra versión")))
        refused(WriteRefused.Reason.READ_ONLY) { writes.create("Appointments", appointment()) }
        assertTrue(engine().sync(SyncTrigger.USER) is SyncOutcome.Completed)
        assertEquals(1, store.pendingChanges())
        assertEquals(0, cloud.admin("SELECT SyncId FROM Appointments WHERE SyncId = ?", id).size)
    }

    @Test
    fun laNubeExigeOtroProtocoloYLaColaSeConserva() {
        cloudPatient()
        linked()
        writes.create("Appointments", appointment())
        meta("minClientProtocol", "9")
        assertTrue(engine().sync(SyncTrigger.USER) is SyncOutcome.ClientOutdated)
        assertEquals(1, store.pendingChanges())
    }

    @Test
    fun otraGeneracionGuardaLoPendienteYNadaLlegaALaNubeNueva() {
        cloudPatient()
        linked()
        val id = writes.create("Appointments", appointment())
        meta("generationPrevious", "gen-1")
        meta("generation", "gen-2")
        assertEquals(SyncOutcome.GenerationPending, engine().sync(SyncTrigger.USER))
        assertEquals(0, store.pendingChanges())
        assertEquals(1, store.savedChanges().size)
        assertEquals(1, store.unsyncedChanges())
        assertEquals(0, cloud.admin("SELECT SyncId FROM Appointments").size)
        meta("generationReady", "gen-2")
        assertTrue(engine().sync(SyncTrigger.USER) is SyncOutcome.Completed)
        assertEquals("Vaciar no toca lo guardado", 1, store.savedChanges().size)
        assertEquals(1 to 0, engine().reapplySaved())
        assertEquals(1, store.pendingChanges())
        assertTrue(engine().sync(SyncTrigger.USER) is SyncOutcome.Completed)
        assertEquals(1, cloud.admin("SELECT SyncId FROM Appointments WHERE SyncId = ?", id).size)
        assertEquals(0, store.unsyncedChanges())
    }

    @Test
    fun vaciarConAlgoEnLaColaFalla() {
        cloudPatient()
        linked()
        writes.create("Appointments", appointment())
        assertThrows(IllegalStateException::class.java) { store.clearSyncedData(SyncEngine.KEPT_META_ON_CLEAR) }
        assertEquals(1, store.pendingChanges())
    }

    @Test
    fun quitadoSubeYDespuesSeBorra() {
        cloudPatient()
        linked()
        val id = writes.create("Appointments", appointment())
        revoke("quitado")
        val outcome = engine().sync(SyncTrigger.USER)
        assertEquals(SyncOutcome.Revoked("quitado", wiped = true), outcome)
        assertEquals(1, cloud.admin("SELECT SyncId FROM Appointments WHERE SyncId = ?", id).size)
        assertEquals(0, store.unsyncedChanges())
        assertTrue(store.rows("Patients").isEmpty())
    }

    @Test
    fun quitadoSinRedSeConservaYLoRecuerda() {
        cloudPatient()
        linked()
        writes.create("Appointments", appointment())
        revoke("quitado")
        cloud.armCut(SqlCloud.Cut(1, SqlCloud.Cut.Mode.BEFORE_APPLY))
        assertEquals(SyncOutcome.Revoked("quitado", wiped = false), engine().sync(SyncTrigger.USER))
        assertEquals(1, store.pendingChanges())
        assertEquals("quitado", store.meta(SyncEngine.REVOKED_REASON))
        refused(WriteRefused.Reason.REVOKED) { writes.create("Appointments", appointment()) }
        assertFalse(store.rows("Patients").isEmpty())
        cloud.armCut(null)
        assertEquals(SyncOutcome.Revoked("quitado", wiped = true), engine().sync(SyncTrigger.AUTOMATIC))
        assertEquals(1, cloud.admin("SELECT SyncId FROM Appointments").size)
    }

    @Test
    fun perdidoBorraSinSubirNadaAunqueHayaAlgoPendiente() {
        cloudPatient()
        linked()
        writes.create("Appointments", appointment())
        revoke("perdido")
        assertEquals(SyncOutcome.Revoked("perdido", wiped = true), engine().sync(SyncTrigger.USER))
        assertEquals(0, cloud.admin("SELECT SyncId FROM Appointments").size)
        assertEquals(0, store.pendingChanges())
    }

    @Test
    fun quitadoQuePasaAPerdidoBorraSinSubir() {
        cloudPatient()
        linked()
        writes.create("Appointments", appointment())
        revoke("quitado")
        cloud.offline = false
        cloud.armCut(SqlCloud.Cut(1, SqlCloud.Cut.Mode.BEFORE_APPLY))
        engine().sync(SyncTrigger.USER)
        cloud.armCut(null)
        revoke("perdido")
        assertEquals(SyncOutcome.Revoked("perdido", wiped = true), engine().sync(SyncTrigger.USER))
        assertEquals(0, cloud.admin("SELECT SyncId FROM Appointments").size)
    }

    @Test
    fun rechazadoConAlgoPendienteNoSubeNiSeBorra() {
        cloudPatient()
        linked()
        writes.create("Appointments", appointment())
        revoke("rechazado")
        assertEquals(SyncOutcome.Revoked("rechazado", wiped = false), engine().sync(SyncTrigger.USER))
        assertEquals(1, store.pendingChanges())
        assertEquals(0, cloud.admin("SELECT SyncId FROM Appointments").size)
    }

    @Test
    fun loAnotadoJustoAntesDeBorrarDetieneElBorrado() {
        cloudPatient()
        linked()
        revoke("quitado")
        var closed = 0
        var reopened = 0
        val guard = object : io.github.marioalr.tpvca.sync.engine.EraseGuard {
            override fun closing() {
                closed++
                // Una escritura que alcanzó a entrar antes de cerrar la puerta
                writes.writer.create("Appointments", "10000000-0000-4000-8000-000000000009", appointment())
            }

            override fun reopen() {
                reopened++
            }
        }
        val outcome = engine(guard).sync(SyncTrigger.USER)
        assertTrue(closed > 0)
        assertEquals(closed, reopened)
        assertEquals(SyncOutcome.Revoked("quitado", wiped = false), outcome)
        assertEquals(1, store.pendingChanges())
        assertFalse(store.rows("Patients").isEmpty())
    }

    @Test
    fun conAlgoGuardadoDosVecesCuatroCientosUnoNoBorran() {
        cloudPatient()
        linked()
        store.apply(StoreChanges(saved = listOf(io.github.marioalr.tpvca.sync.engine.SavedChange(0, "este-equipo", "g", "PatientAppointment", "x", "tombstone", "t", 3, 21, "t"))))
        var now = 0L
        val rejecting = object : io.github.marioalr.tpvca.sync.hrana.HranaConnection {
            override fun query(statements: List<io.github.marioalr.tpvca.sync.hrana.Statement>) = throw io.github.marioalr.tpvca.sync.hrana.HranaException(io.github.marioalr.tpvca.sync.hrana.HranaException.UNAUTHORIZED, "401")

            override fun snapshot(before: List<io.github.marioalr.tpvca.sync.hrana.Statement>, statements: List<io.github.marioalr.tpvca.sync.hrana.Statement>) = query(statements).let { error("no llega") }
        }
        val engine = SyncEngine(rejecting, store, SyncEngineConfig("device-b"), elapsedMillis = { now })
        assertEquals(SyncOutcome.TokenRejected(wiped = false), engine.sync(SyncTrigger.USER))
        now = 3_600_000L
        assertEquals(SyncOutcome.TokenRejected(wiped = false), engine.sync(SyncTrigger.USER))
        assertEquals(1, store.unsyncedChanges())
        assertFalse(store.rows("Patients").isEmpty())
    }

    @Test
    fun laFilaSinSuPrincipalSeApartaAlSubirYSigueContando() {
        cloudPatient()
        linked()
        // Una cita cuyo expediente no está aquí: no sube, no se pierde y sigue contando como pendiente
        val id = writes.writer.create("Appointments", "10000000-0000-4000-8000-000000000009", appointment() + ("PatientRecordSyncId" to "10000000-0000-4000-8000-00000000dead"))["SyncId"] as String
        assertTrue(engine().sync(SyncTrigger.USER) is SyncOutcome.Completed)
        assertEquals(1, store.pendingChanges())
        assertEquals(ParkDirection.PUSH, store.parkedRows().single { it.syncId == id }.direction)
        assertEquals(0, cloud.admin("SELECT SyncId FROM Appointments").size)
    }

    @Test
    fun sinRedElFolioLlevaLaLetraYNoSeRepite() {
        linked()
        val first = writes.newPatient("Uno").second
        assertTrue(first, first.matches(Regex("EXP-[0-9]{4}-001")))
        cloud.offline = true
        val second = writes.newPatient("Dos").second
        assertTrue(second, second.matches(Regex("EXP-[0-9]{4}-T1-001")))
        // Se borró el expediente de aquí (bajó todo otra vez y la PC le cambió el número): lo guardado fuera de la base no repite el número
        store.rows("Patients").clear()
        val third = writes.newPatient("Tres").second
        assertTrue(third, third.matches(Regex("EXP-[0-9]{4}-T1-002")))
    }

    @Test
    fun conLaCuotaAgotadaNiSePreguntaAlContador() {
        linked()
        store.apply(StoreChanges(meta = mapOf(SyncEngine.AUTH_FIRST_401 to "2026-01-01T00:00:00Z")))
        val before = cloud.executed.size
        assertTrue(writes.newPatient("Uno").second.contains("-T1-"))
        assertEquals(before, cloud.executed.size)
    }

    @Test
    fun ningunaInstruccionEsDdl() {
        cloudPatient()
        linked()
        writes.create("Appointments", appointment())
        writes.newPatient("Uno")
        engine().sync(SyncTrigger.USER)
        val ddl = Regex("^\\s*(CREATE|ALTER|DROP)\\b", RegexOption.IGNORE_CASE)
        assertTrue(cloud.executed.filter { ddl.containsMatchIn(it) }.toString(), cloud.executed.none { ddl.containsMatchIn(it) })
    }

    @Test
    fun unInventarioMalContadoNoImpideCobrar() {
        cloudPatient()
        linked()
        val sale = writes.registerSale(listOf(SaleLine(productSyncId = product, quantity = 3, unitPrice = BigDecimal.TEN)))!!
        assertEquals(-2L, store.rows("Products").getValue(product)["CurrentStock"])
        val movements = store.rows("InventoryMovements").values
        assertEquals(1, movements.size)
        assertNull(movements.single()["BatchSyncId"])
        assertTrue(engine().sync(SyncTrigger.USER) is SyncOutcome.Completed)
        assertEquals(1, cloud.admin("SELECT SyncId FROM Sales WHERE SyncId = ?", sale.syncId).size)
    }

    @Test
    fun elArnesDeEscenariosDetectaUnaDiferencia() {
        // Si el arnés dejara pasar todo, los escenarios no dirían nada
        val original = Contracts.file("scenarios/lww-and-ties.json").readText()
        val tampered = File.createTempFile("escenario", ".json").also { it.deleteOnExit() }
        tampered.writeText(original.replaceFirst("\"pending\": 0", "\"pending\": 7"))
        assertFalse(original == tampered.readText())
        assertTrue(ScenarioHarness(tampered).run().isNotEmpty())
    }
}
