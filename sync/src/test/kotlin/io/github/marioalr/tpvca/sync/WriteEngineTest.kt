package io.github.marioalr.tpvca.sync

import io.github.marioalr.tpvca.domain.devices.DeviceRole
import io.github.marioalr.tpvca.sync.engine.ParkDirection
import io.github.marioalr.tpvca.sync.engine.RowMapper
import io.github.marioalr.tpvca.sync.engine.RowWrite
import io.github.marioalr.tpvca.sync.engine.SaleLine
import io.github.marioalr.tpvca.sync.engine.StoreChanges
import io.github.marioalr.tpvca.sync.engine.SyncEngine
import io.github.marioalr.tpvca.sync.engine.SyncEngineConfig
import io.github.marioalr.tpvca.sync.engine.SyncOutcome
import io.github.marioalr.tpvca.sync.engine.SyncTrigger
import io.github.marioalr.tpvca.sync.engine.WriteEngine
import io.github.marioalr.tpvca.sync.engine.WriteRefused
import io.github.marioalr.tpvca.sync.engine.unsyncedChanges
import io.github.marioalr.tpvca.sync.hrana.HranaException
import io.github.marioalr.tpvca.sync.hrana.HranaValue
import io.github.marioalr.tpvca.sync.hrana.Statement
import io.github.marioalr.tpvca.sync.hrana.text
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

    /** Para preparar lo que un caso de uso no dejaría escribir. */
    private val localWriter = io.github.marioalr.tpvca.sync.engine.LocalWriter(store, { "device-b" })

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
        refused(WriteRefused.Reason.FORBIDDEN) { writes.cancelSale("no-importa") }
        refused(WriteRefused.Reason.FORBIDDEN) { writes.edit("Products", product, mapOf("Price" to "1")) }
        refused(WriteRefused.Reason.FORBIDDEN) { writes.closeBatch("x") }
        refused(WriteRefused.Reason.FORBIDDEN) { writes.delete("Patients", patient) }
        // Un movimiento que no es una entrada de compra, tampoco
        refused(WriteRefused.Reason.FORBIDDEN) { writes.create("InventoryMovements", mapOf("ProductSyncId" to product, "MovementType" to "6")) }
        assertEquals(0, store.pendingChanges())
        assertEquals(before, store.rowCounts())
    }

    @Test
    fun elCelularAgendaPeroNoRegistraEntradasHastaElOchoVeinte() {
        cloudPatient()
        linked(DeviceRole.AndroidMobileAdmin, "C1")
        writes.create("Appointments", appointment())
        assertEquals(1, store.pendingChanges())
        // Sin poder tocar Products y con CurrentStock como valor, la entrada no subiría las existencias: se niega hasta el 8.20
        refused(WriteRefused.Reason.FORBIDDEN) { writes.registerStockEntry(product, "L-1", null, 5, BigDecimal("3.5"), "Pedido LUIN #1", "Dr.") }
        refused(WriteRefused.Reason.FORBIDDEN) { writes.create("ProductBatches", mapOf("ProductSyncId" to product)) }
        refused(WriteRefused.Reason.FORBIDDEN) { writes.create("InventoryMovements", mapOf("ProductSyncId" to product, "MovementType" to "0")) }
        assertEquals(1, store.pendingChanges())
        assertTrue(engine().sync(SyncTrigger.USER) is SyncOutcome.Completed)
        assertEquals(0, store.pendingChanges())
    }

    @Test
    fun laTabletRegistraLaEntradaConSuLoteYSusExistencias() {
        cloudPatient()
        linked()
        val batch = writes.registerStockEntry(product, "L-1", null, 5, BigDecimal("3.5"), "Pedido LUIN #1", "Dr.")
        assertEquals(6L, store.rows("Products").getValue(product)["CurrentStock"])
        assertTrue(engine().sync(SyncTrigger.USER) is SyncOutcome.Completed)
        assertEquals("L-1", cloud.admin("SELECT BatchNumber FROM ProductBatches WHERE SyncId = ?", batch).single()["BatchNumber"]?.text)
        assertEquals("6", cloud.admin("SELECT CurrentStock FROM Products WHERE SyncId = ?", product).single()["CurrentStock"]?.text)
    }

    @Test
    fun laColaYLaEscrituraVanJuntasOSeDeshacenJuntas() {
        cloudPatient()
        linked()
        assertThrows(IllegalStateException::class.java) {
            store.transaction {
                localWriter.create("Appointments", "10000000-0000-4000-8000-000000000009", appointment())
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
        assertEquals(SyncEngine.Reapplied(1, 0, 0), engine().reapplySaved())
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
                localWriter.create("Appointments", "10000000-0000-4000-8000-000000000009", appointment())
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
        val id = localWriter.create("Appointments", "10000000-0000-4000-8000-000000000009", appointment() + ("PatientRecordSyncId" to "10000000-0000-4000-8000-00000000dead"))["SyncId"] as String
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

    // ---------------------------------------------------------------------------------------------------------------- la revisión de K5

    private val category = "10000000-0000-4000-8000-000000000003"

    private fun stampOf(table: String, id: String) = store.rows(table).getValue(id)["UpdatedAtUtc"] as String

    private fun readOnly() = store.apply(StoreChanges(meta = mapOf(SyncEngine.READ_ONLY to "otra versión")))

    private fun cloudSchemaKeepsMatching() = store.apply(StoreChanges(meta = mapOf(SyncEngine.CLOUD_SCHEMA_SEEN to SyncContract.bundledSchema.fingerprint)))

    @Test
    fun elSoloLecturaSigueALaNubeAunqueLaAppNoCambie() {
        cloudPatient()
        linked()
        assertNull(store.meta(SyncEngine.READ_ONLY))
        // La PC cambia el esquema: la misma app, otra huella
        meta("schema", "otra-huella")
        meta("manifest", "{}")
        val first = engine().sync(SyncTrigger.USER) as SyncOutcome.Completed
        assertTrue(first.readOnly != null)
        assertTrue(store.meta(SyncEngine.READ_ONLY) != null)
        refused(WriteRefused.Reason.READ_ONLY) { writes.create("Appointments", appointment()) }
        // Y la nube se pone al corriente: se quita sola
        meta("schema", SyncContract.bundledSchema.fingerprint)
        meta("manifest", SyncContract.bundledSchemaText())
        assertNull((engine().sync(SyncTrigger.USER) as SyncOutcome.Completed).readOnly)
        assertNull(store.meta(SyncEngine.READ_ONLY))
        writes.create("Appointments", appointment())
    }

    @Test
    fun laHuellaSinCambiosNoVuelveALeerElManifiesto() {
        linked()
        cloud.executed.clear()
        engine().sync(SyncTrigger.USER)
        assertFalse(cloud.executed.contains(Statements.META_ALL))
        assertTrue(cloud.executed.contains(Statements.META_SCHEMA))
    }

    /** Una versión de aquí y otra en la nube que dicen cosas distintas: combinar deja algo distinto de la nube. */
    private fun divergePatient() {
        val stamp = "2026-06-01 00:00:00.0000000"
        store.apply(StoreChanges(writes = listOf(RowWrite("Patients", patient, mapOf("Phone" to "111", "UpdatedAtUtc" to stamp, "FieldStampsJson" to "{\"Phone\":\"$stamp\",\"UpdatedAtUtc\":\"$stamp\"}"), isNew = false))))
        val later = "2026-07-01 00:00:00.0000000"
        cloud.admin("UPDATE Patients SET UpdatedAtUtc = ?, SyncedAtUtc = ?, Allergies = 'Sulfas', FieldStampsJson = ? WHERE SyncId = ?", later, cloud.nowText(), "{\"Allergies\":\"$later\",\"UpdatedAtUtc\":\"$later\"}", patient)
    }

    @Test
    fun elCelularNuncaAnotaUnExpedienteAlCombinar() {
        cloudPatient()
        linked(DeviceRole.AndroidMobileAdmin, "C1")
        divergePatient()
        assertTrue(engine().sync(SyncTrigger.USER) is SyncOutcome.Completed)
        assertEquals("Sulfas", store.rows("Patients").getValue(patient)["Allergies"])
        assertEquals(0, store.pendingChanges())
        assertEquals("La nube no recibe nada de un celular", "2026-07-01 00:00:00.0000000", cloud.admin("SELECT UpdatedAtUtc FROM Patients WHERE SyncId = ?", patient).single()["UpdatedAtUtc"]?.text)
    }

    @Test
    fun laTabletSiSubeLoCombinado() {
        cloudPatient()
        linked()
        divergePatient()
        assertTrue(engine().sync(SyncTrigger.USER) is SyncOutcome.Completed)
        assertEquals("111", cloud.admin("SELECT Phone FROM Patients WHERE SyncId = ?", patient).single()["Phone"]?.text)
    }

    @Test
    fun unRolSinConfirmarDetieneLasEscrituras() {
        cloudPatient()
        linked()
        cloud.admin("DELETE FROM DeviceRegistry")
        assertEquals(SyncOutcome.NotApproved, engine().sync(SyncTrigger.USER))
        refused(WriteRefused.Reason.NOT_APPROVED) { writes.create("Appointments", appointment()) }
        approve(DeviceRole.WindowsDesktop)
        assertTrue(engine().sync(SyncTrigger.USER) is SyncOutcome.WrongRole)
        refused(WriteRefused.Reason.NOT_APPROVED) { writes.create("Appointments", appointment()) }
    }

    private fun saved(entityType: String, syncId: String, kind: String, payload: String) =
        io.github.marioalr.tpvca.sync.engine.SavedChange(0, "este-equipo", "g", entityType, syncId, kind, payload, 3, 21, "t")

    private fun savedRow(table: String, syncId: String, vararg values: Pair<String, Any?>): String {
        val row = localWriter.buildRow(SyncContract.bundledSchema.table(table)!!, syncId, values.toMap(), "2026-01-01 00:00:00.0000000")
        return RowMapper.serialize(RowMapper.remoteRow(SyncContract.bundledSchema.table(table)!!, row))
    }

    @Test
    fun volverAAplicarSinPrincipalConservaSuCopiaHastaQueLlegueYSeAnote() {
        linked()
        val id = "10000000-0000-4000-8000-000000000009"
        store.apply(StoreChanges(saved = listOf(saved("PatientAppointment", id, "row", savedRow("Appointments", id, "PatientRecordSyncId" to patient, "Reason" to "X")))))
        val first = engine().reapplySaved()
        assertEquals(SyncEngine.Reapplied(0, 0, 1), first)
        assertEquals("Sigue guardada", 1, store.savedChanges().size)
        assertEquals("No se aplicó ni se anotó", 0, store.pendingChanges())
        assertTrue("No se aparta: la aplicaría sin anotarla", store.parkedRows().isEmpty())
        assertTrue(store.rows("Appointments").isEmpty())
        assertTrue(store.meta(SyncEngine.NOTICES).orEmpty().contains(io.github.marioalr.tpvca.sync.engine.SyncNotices.REAPPLY_NO_PRINCIPAL))
        // Llega su expediente y se vuelve a intentar: ahora sí
        cloudPatient()
        assertTrue(engine().sync(SyncTrigger.USER) is SyncOutcome.Completed)
        assertEquals(SyncEngine.Reapplied(1, 0, 0), engine().reapplySaved())
        assertTrue(store.savedChanges().isEmpty())
        assertEquals(1, store.pendingChanges())
        assertTrue(engine().sync(SyncTrigger.USER) is SyncOutcome.Completed)
        assertEquals("X", cloud.admin("SELECT Reason FROM Appointments WHERE SyncId = ?", id).single()["Reason"]?.text)
    }

    @Test
    fun unBorradoQueImpideUnaRestrictSigueGuardadoConSuAviso() {
        cloudPatient()
        linked()
        store.apply(StoreChanges(saved = listOf(saved("Category", category, "tombstone", "2026-01-01 00:00:00.0000000"))))
        assertEquals(SyncEngine.Reapplied(0, 0, 1), engine().reapplySaved())
        assertEquals(1, store.savedChanges().size)
        assertEquals(setOf(category), store.existing("Categories", listOf(category)))
        assertTrue(store.meta(SyncEngine.NOTICES).orEmpty().contains(io.github.marioalr.tpvca.sync.engine.SyncNotices.REAPPLY_DELETE_BLOCKED))
        // Quitado lo que lo impedía, se hace
        store.apply(StoreChanges(deletes = mapOf("Products" to setOf(product))))
        assertEquals(SyncEngine.Reapplied(0, 1, 0), engine().reapplySaved())
        assertTrue(store.savedChanges().isEmpty())
        assertEquals("Category", store.outbox().single().entityType)
    }

    @Test
    fun volverAAplicarTieneLosPermisosDeCualquierEscritura() {
        cloudPatient()
        linked(DeviceRole.AndroidMobileAdmin, "C1")
        val appt = "10000000-0000-4000-8000-000000000009"
        store.apply(
            StoreChanges(
                saved = listOf(
                    saved("PatientRecord", patient, "row", savedRow("Patients", patient, "FullName" to "Otra")),
                    saved("PatientAppointment", appt, "row", savedRow("Appointments", appt, "PatientRecordSyncId" to patient)),
                ),
            ),
        )
        // Lo que este rol no puede escribir se queda guardado; lo que sí, se aplica
        assertEquals(SyncEngine.Reapplied(1, 0, 1), engine().reapplySaved())
        assertEquals("PatientRecord", store.savedChanges().single().entityType)
        assertEquals("Ana", store.rows("Patients").getValue(patient)["FullName"])
        assertEquals("PatientAppointment", store.outbox().single().entityType)
        readOnly()
        assertEquals(WriteRefused.Reason.READ_ONLY, assertThrows(WriteRefused::class.java) { engine().reapplySaved() }.reason)
        store.apply(StoreChanges(metaRemoved = setOf(SyncEngine.READ_ONLY, SyncEngine.DEVICE_ROLE)))
        assertEquals(WriteRefused.Reason.NOT_APPROVED, assertThrows(WriteRefused::class.java) { engine().reapplySaved() }.reason)
    }

    @Test
    fun unaBajadaNoReviveLoQueAquiSeBorroSinSubir() {
        cloudPatient()
        linked()
        val appt = "10000000-0000-4000-8000-000000000009"
        cloud.admin("INSERT INTO Appointments (SyncId, UpdatedAtUtc, SyncedAtUtc, PatientRecordSyncId) VALUES (?, '2026-01-02 00:00:00.0000000', ?, ?)", appt, cloud.nowText(), patient)
        assertTrue(engine().sync(SyncTrigger.USER) is SyncOutcome.Completed)
        writes.delete("Appointments", appt)
        val deletedAt = store.tombstones.getValue("PatientAppointment" to appt).deletedAtUtc
        // En solo lectura el borrado no sube, y la fila vuelve a bajar
        readOnly()
        cloudSchemaKeepsMatching()
        cloud.admin("UPDATE Appointments SET UpdatedAtUtc = ?, SyncedAtUtc = ? WHERE SyncId = ?", "2026-01-03 00:00:00.0000000", cloud.nowText(), appt)
        assertTrue(engine().sync(SyncTrigger.USER) is SyncOutcome.Completed)
        assertFalse("Más vieja que el borrado: no revive", appt in store.rows("Appointments"))
        assertEquals("Y el borrado sigue por subir", 1, store.pendingChanges())
        // Con la misma marca gana el borrado, como en la nube
        cloud.admin("UPDATE Appointments SET UpdatedAtUtc = ?, SyncedAtUtc = ? WHERE SyncId = ?", deletedAt, cloud.nowText(), appt)
        engine().sync(SyncTrigger.USER)
        assertFalse(appt in store.rows("Appointments"))
        // Una edición más nueva que el borrado sí lo revive: es el LWW de siempre
        cloud.admin("UPDATE Appointments SET UpdatedAtUtc = ?, SyncedAtUtc = ? WHERE SyncId = ?", "2099-01-01 00:00:00.0000000", cloud.nowText(), appt)
        engine().sync(SyncTrigger.USER)
        assertTrue(appt in store.rows("Appointments"))
    }

    private class RecordingBeat : io.github.marioalr.tpvca.sync.engine.PushHeartbeat {
        var pending = -1

        override fun statements(pending: Int, oldest: String?): List<Statement> {
            this.pending = pending
            return listOf(Statement(Statements.HEARTBEAT_READ_NONCE, listOf(HranaValue.Text("device-b"))), Statement(Statements.META_SCHEMA))
        }

        override fun onWritten(before: io.github.marioalr.tpvca.sync.hrana.ResultSet) = Unit
    }

    /** Una fila que la nube no va a aceptar: ya tiene una más nueva. */
    private fun rejectedRow(): String {
        val id = writes.create("Appointments", appointment())
        cloud.admin("INSERT INTO Appointments (SyncId, UpdatedAtUtc, SyncedAtUtc, PatientRecordSyncId, Reason) VALUES (?, '2099-01-01 00:00:00.0000000', ?, ?, 'De la nube')", id, cloud.nowText(), patient)
        return id
    }

    @Test
    fun laColaNoSeVaciaHastaResolverLoRechazado() {
        cloudPatient()
        linked()
        val id = rejectedRow()
        val beat = RecordingBeat()
        // Un 401 en el latido, después de subir y antes de releer: nada salió de la cola
        cloud.failWith = { list -> if (list.any { it.sql == Statements.HEARTBEAT_READ_NONCE }) HranaException(HranaException.UNAUTHORIZED, "401") else null }
        val failed = SyncEngine(cloud.connection, store, SyncEngineConfig("device-b"), pushHeartbeat = beat).sync(SyncTrigger.USER)
        assertTrue(failed is SyncOutcome.TokenRejected)
        assertEquals("Se conserva", 1, store.pendingChanges())
        assertEquals("El latido dice lo que quedaría", 0, beat.pending)
        // Tampoco si falla la relectura
        cloud.failWith = { list -> if (list.any { it.sql.startsWith("SELECT * FROM \"Appointments\" WHERE \"SyncId\" IN") }) HranaException(HranaException.NETWORK_ERROR, "red") else null }
        assertTrue(SyncEngine(cloud.connection, store, SyncEngineConfig("device-b"), pushHeartbeat = beat).sync(SyncTrigger.USER) is SyncOutcome.Failed)
        assertEquals(1, store.pendingChanges())
        // Con todo bien: se aplica la de la nube y se vacía
        cloud.failWith = null
        engine().tokenReplaced()
        assertTrue(SyncEngine(cloud.connection, store, SyncEngineConfig("device-b"), pushHeartbeat = beat).sync(SyncTrigger.USER) is SyncOutcome.Completed)
        assertEquals(0, store.pendingChanges())
        assertEquals("De la nube", store.rows("Appointments").getValue(id)["Reason"])
    }

    @Test
    fun enLaRedVanSubirLatidoYReleerComoEnLaGrabacion() {
        cloudPatient()
        linked()
        rejectedRow()
        cloud.executed.clear()
        assertTrue(SyncEngine(cloud.connection, store, SyncEngineConfig("device-b"), pushHeartbeat = RecordingBeat()).sync(SyncTrigger.USER) is SyncOutcome.Completed)
        val upsert = cloud.executed.indexOfFirst { it.startsWith("INSERT INTO \"Appointments\"") }
        val beat = cloud.executed.indexOf(Statements.HEARTBEAT_READ_NONCE)
        val reread = cloud.executed.indexOfFirst { it.startsWith("SELECT * FROM \"Appointments\" WHERE \"SyncId\" IN") }
        assertTrue("$upsert $beat $reread", upsert in 0 until beat && beat < reread)
    }

    @Test
    fun loQueLaNubeNoTieneDeNingunaFormaSeVuelveAAnotar() {
        // Sin fila ni lápida en la nube: la cerca dejaría 0 filas, pero esta vez con la misma generación. Se simula con un disparador que tira la escritura
        cloudPatient()
        linked()
        val id = writes.create("Appointments", appointment())
        cloud.admin("CREATE TRIGGER tirar BEFORE INSERT ON Appointments BEGIN SELECT RAISE(IGNORE); END")
        assertTrue(engine().sync(SyncTrigger.USER) is SyncOutcome.Completed)
        assertEquals(id, store.outbox().single().syncId)
    }

    @Test
    fun laSubidaReduceElLoteYUnaFilaQueNoPasaSeApartaSinDetenerLaBajada() {
        cloudPatient()
        linked()
        val big = writes.create("Appointments", appointment() + ("Reason" to "Enorme"))
        val small = (1..4).map { writes.create("Appointments", appointment()) }
        val upserts = { list: List<Statement> -> list.count { it.sql.startsWith("INSERT INTO \"Appointments\"") } }
        // Más de una fila no cabe; y esa, ni sola
        cloud.tooLarge = { list -> upserts(list) > 1 || list.any { statement -> statement.args.any { it.text == big } && upserts(list) == 1 } }
        cloud.admin("INSERT INTO Categories (SyncId, UpdatedAtUtc, SyncedAtUtc, Name) VALUES ('10000000-0000-4000-8000-0000000000aa', '2026-01-01 00:00:00.0000000', ?, 'De la nube')", cloud.nowText())
        assertTrue(engine().sync(SyncTrigger.USER) is SyncOutcome.Completed)
        assertEquals("Las demás subieron", small.toSet(), cloud.admin("SELECT SyncId FROM Appointments").map { it["SyncId"]!!.text }.toSet())
        assertEquals("La bajada siguió", setOf("10000000-0000-4000-8000-0000000000aa"), store.existing("Categories", listOf("10000000-0000-4000-8000-0000000000aa")))
        assertEquals("No se tira: sigue pendiente", 1, store.pendingChanges())
        assertEquals(ParkDirection.PUSH, store.parkedRows().single { it.syncId == big }.direction)
        assertEquals(SyncEngine.TOO_BIG_TO_PUSH, store.parkedRows().single { it.syncId == big }.reason)
        // Cuando ya pasa, sube y sale de las apartadas
        cloud.tooLarge = { false }
        assertTrue(engine().sync(SyncTrigger.USER) is SyncOutcome.Completed)
        assertEquals(0, store.pendingChanges())
        assertEquals(1, cloud.admin("SELECT SyncId FROM Appointments WHERE SyncId = ?", big).size)
    }

    @Test
    fun unaOperacionQueNoCabeJuntaSeMandaFilaPorFila() {
        cloudPatient()
        linked()
        val sale = writes.registerSale(listOf(SaleLine(productSyncId = product, quantity = 1, unitPrice = BigDecimal.TEN)))!!
        cloud.tooLarge = { list -> list.count { it.sql.contains("ON CONFLICT(\"SyncId\") DO UPDATE") } > 1 }
        assertTrue(engine().sync(SyncTrigger.USER) is SyncOutcome.Completed)
        assertEquals(0, store.pendingChanges())
        assertEquals(1, cloud.admin("SELECT SyncId FROM Sales WHERE SyncId = ?", sale.syncId).size)
    }

    @Test
    fun otroErrorDeLaSubidaNoAparta() {
        cloudPatient()
        linked()
        writes.create("Appointments", appointment())
        cloud.failWith = { list -> if (list.any { it.sql.startsWith("INSERT INTO \"Appointments\"") }) HranaException(HranaException.NETWORK_ERROR, "red") else null }
        assertTrue(engine().sync(SyncTrigger.USER) is SyncOutcome.Failed)
        assertTrue(store.parkedRows().isEmpty())
        assertEquals(1, store.pendingChanges())
    }

    // ---------------------------------------------------------------------------------------------------------------- ventas y existencias

    /** Un producto de [units] piezas por paquete, con [opened] sueltas y [stock] paquetes. */
    private fun stockProduct(stock: Long, units: Long, opened: Long, price: String = "100") {
        store.apply(StoreChanges(writes = listOf(RowWrite("Products", product, mapOf("CurrentStock" to stock, "UnitsPerPackage" to units, "OpenedUnitsRemaining" to opened, "Price" to price), isNew = false))))
    }

    private fun legacySale(vararg lines: Triple<String, Long, Long?>): String {
        val sale = "10000000-0000-4000-8000-0000000000b0"
        localWriter.create("Sales", sale, mapOf("Folio" to "TKT-20200101-0001", "LedgerVersion" to "0", "Date" to "2020-01-01 10:00:00.0000000"))
        lines.forEachIndexed { index, (id, quantity, individual) ->
            localWriter.create(
                "SaleItems", id,
                mapOf("SaleSyncId" to sale, "ProductSyncId" to product, "Quantity" to quantity.toString(), "UnitPrice" to "10", "IsIndividualUnits" to individual?.toString()),
            )
            assertTrue(index >= 0)
        }
        return sale
    }

    private fun movements() = store.rows("InventoryMovements").values.toList()

    @Test
    fun ticketDeAntesConPiezasDevuelveLasPiezasUnaSolaVez() {
        cloudPatient()
        linked()
        // El ejemplo: 3 piezas, 10 por paquete, 8 sueltas abiertas. 8 + 3 = 11: se completa 1 paquete y queda 1 suelta
        stockProduct(stock = 5, units = 10, opened = 8)
        val item = "10000000-0000-4000-8000-0000000000b1"
        val sale = legacySale(Triple(item, 3L, 1L))
        writes.cancelSale(sale)
        val row = store.rows("Products").getValue(product)
        assertEquals(6L, row["CurrentStock"])
        assertEquals(1L, row["OpenedUnitsRemaining"])
        val only = movements().single()
        assertEquals(1L, only["Delta"])
        assertEquals(io.github.marioalr.tpvca.sync.rules.SyncIds.derived("return:pieces:", item), only["SyncId"])
        assertEquals(item, only["SaleItemSyncId"])
    }

    @Test
    fun ticketDeAntesConPaquetesYPiezasDevuelveCadaUnoPorSuLado() {
        cloudPatient()
        linked()
        stockProduct(stock = 5, units = 10, opened = 8)
        val sale = legacySale(Triple("10000000-0000-4000-8000-0000000000b1", 3L, 1L), Triple("10000000-0000-4000-8000-0000000000b2", 2L, 0L))
        writes.cancelSale(sale)
        assertEquals("2 paquetes y 1 que se completa", 8L, store.rows("Products").getValue(product)["CurrentStock"])
        assertEquals(1L, store.rows("Products").getValue(product)["OpenedUnitsRemaining"])
        assertEquals(listOf(1L, 2L), movements().map { it["Delta"] as Long }.sorted())
        assertTrue(movements().any { it["SyncId"] == io.github.marioalr.tpvca.sync.rules.SyncIds.derived("return:", sale, product) && it["Delta"] == 2L })
    }

    @Test
    fun ticketDeAntesVaAlLoteDeSuSalidaPorElFolioOElOriginal() {
        cloudPatient()
        linked()
        val batch = writes.registerStockEntry(product, "L-1", null, 1, null, "Entrada", "Dr.")
        val sale = legacySale(Triple("10000000-0000-4000-8000-0000000000b2", 1L, 0L))
        localWriter.create(
            "InventoryMovements", "10000000-0000-4000-8000-0000000000b3",
            mapOf("ProductSyncId" to product, "BatchSyncId" to batch, "MovementType" to "6", "Quantity" to "1", "Delta" to "-1", "Date" to "2020-01-01 10:00:00.0000000", "Reason" to "Venta POS TKT-20200101-0001 - Paciente/Cliente: X (Efectivo)"),
        )
        writes.cancelSale(sale)
        assertEquals(batch, movements().single { it["MovementType"] == 7L }["BatchSyncId"])
    }

    @Test
    fun ticketDeAntesConElProductoBorradoSeCancelaIgual() {
        cloudPatient()
        linked()
        val sale = legacySale(Triple("10000000-0000-4000-8000-0000000000b1", 3L, null))
        store.apply(StoreChanges(deletes = mapOf("Products" to setOf(product))))
        assertTrue(writes.cancelSale(sale))
        assertEquals(1L, store.rows("Sales").getValue(sale)["IsCancelled"])
        assertTrue(movements().isEmpty())
    }

    @Test
    fun laDevolucionDePiezasSoloVaAUnLoteQueExista() {
        cloudPatient()
        linked()
        stockProduct(stock = 5, units = 10, opened = 8)
        val sale = legacySale(Triple("10000000-0000-4000-8000-0000000000b1", 3L, 1L))
        // Su última salida dice un lote que aquí ya no está
        localWriter.create("InventoryMovements", "10000000-0000-4000-8000-0000000000b3", mapOf("ProductSyncId" to product, "BatchSyncId" to "10000000-0000-4000-8000-0000000000ff", "MovementType" to "5", "Delta" to "-1", "Date" to "2020-01-02 10:00:00.0000000"))
        writes.cancelSale(sale)
        assertNull(movements().single { it["MovementType"] == 7L }["BatchSyncId"])
    }

    @Test
    fun sinTodosLosMovimientosDelTicketPideSincronizarYNoCancelaNada() {
        cloudPatient()
        linked()
        val sale = writes.registerSale(listOf(SaleLine(productSyncId = product, quantity = 2, unitPrice = BigDecimal.TEN)))!!
        store.rows("InventoryMovements").clear()
        refused(WriteRefused.Reason.NEEDS_SYNC) { writes.cancelSale(sale.syncId) }
        assertEquals(0L, store.rows("Sales").getValue(sale.syncId)["IsCancelled"])
    }

    @Test
    fun lasPiezasSueltasAlVenderYAlCancelar() {
        cloudPatient()
        linked()
        stockProduct(stock = 5, units = 10, opened = 1)
        // 3 piezas con 1 suelta: abre un paquete y quedan 8
        val first = writes.registerSale(listOf(SaleLine(productSyncId = product, quantity = 3, unitPrice = BigDecimal.TEN, individualUnits = true)))!!
        var row = store.rows("Products").getValue(product)
        assertEquals(4L, row["CurrentStock"])
        assertEquals(8L, row["OpenedUnitsRemaining"])
        assertEquals(1L, store.rows("SaleItems").values.single()["IsIndividualUnits"])
        // Con las sueltas que hay no abre otro ni deja movimiento
        val before = movements().size
        writes.registerSale(listOf(SaleLine(productSyncId = product, quantity = 2, unitPrice = BigDecimal.TEN, individualUnits = true)))
        row = store.rows("Products").getValue(product)
        assertEquals(4L, row["CurrentStock"])
        assertEquals(6L, row["OpenedUnitsRemaining"])
        assertEquals(before, movements().size)
        // Cancelar el primero: 6 + 3 = 9, no completa ninguno
        writes.cancelSale(first.syncId)
        row = store.rows("Products").getValue(product)
        assertEquals(4L, row["CurrentStock"])
        assertEquals(9L, row["OpenedUnitsRemaining"])
    }

    @Test
    fun lasPiezasQueCompletanUnPaqueteVuelvenALasExistencias() {
        cloudPatient()
        linked()
        stockProduct(stock = 5, units = 10, opened = 1)
        val sale = writes.registerSale(listOf(SaleLine(productSyncId = product, quantity = 3, unitPrice = BigDecimal.TEN, individualUnits = true)))!!
        store.apply(StoreChanges(writes = listOf(RowWrite("Products", product, mapOf("OpenedUnitsRemaining" to 9L), isNew = false))))
        writes.cancelSale(sale.syncId)
        val row = store.rows("Products").getValue(product)
        assertEquals(5L, row["CurrentStock"])
        assertEquals(2L, row["OpenedUnitsRemaining"])
    }

    @Test
    fun unServicioEnLaVentaNoMueveExistencias() {
        cloudPatient()
        val service = "10000000-0000-4000-8000-0000000000c0"
        cloud.admin("INSERT INTO ClinicalServices (SyncId, UpdatedAtUtc, SyncedAtUtc, Code, Name) VALUES (?, '2026-01-01 00:00:00.0000000', ?, 'SERV-001', 'Consulta')", service, cloud.nowText())
        linked()
        val sale = writes.registerSale(listOf(SaleLine(serviceSyncId = service, quantity = 1, unitPrice = BigDecimal("300"))))!!
        val item = store.rows("SaleItems").values.single()
        assertEquals(1L, item["IsService"])
        assertEquals("SERV-001", item["Code"])
        assertEquals(service, item["ClinicalServiceSyncId"])
        assertNull(item["ProductSyncId"])
        assertTrue(movements().isEmpty())
        assertEquals("300", store.rows("Sales").getValue(sale.syncId)["Total"])
        assertTrue(engine().sync(SyncTrigger.USER) is SyncOutcome.Completed)
        assertEquals(1, cloud.admin("SELECT SyncId FROM SaleItems WHERE ClinicalServiceSyncId = ?", service).size)
        // Cancelar no toca el inventario
        val stock = store.rows("Products").getValue(product)["CurrentStock"]
        writes.cancelSale(sale.syncId)
        assertEquals(stock, store.rows("Products").getValue(product)["CurrentStock"])
    }

    @Test
    fun seCancelaElTicketPorSuSyncIdAunqueElFolioSeRepita() {
        cloudPatient()
        linked()
        val first = writes.registerSale(listOf(SaleLine(serviceSyncId = null, quantity = 1, unitPrice = BigDecimal.TEN)))!!
        val second = writes.registerSale(listOf(SaleLine(serviceSyncId = null, quantity = 1, unitPrice = BigDecimal.TEN)))!!
        store.apply(StoreChanges(writes = listOf(RowWrite("Sales", second.syncId, mapOf("Folio" to first.folio), isNew = false))))
        writes.cancelSale(second.syncId)
        assertEquals(0L, store.rows("Sales").getValue(first.syncId)["IsCancelled"])
        assertEquals(1L, store.rows("Sales").getValue(second.syncId)["IsCancelled"])
        refused(WriteRefused.Reason.CONFLICT) { writes.cancelSale("10000000-0000-4000-8000-00000000dead") }
    }

    /** Sin ninguna parte que escribir, el producto no se toca ni sube: `Close` de la PC solo lo cambia cuando escribe una. */
    @Test
    fun cerrarUnLoteSinNadaQueDescontarNoTocaElProducto() {
        cloudPatient()
        linked()
        val batch = "10000000-0000-4000-8000-0000000000f1"
        localWriter.create("ProductBatches", batch, mapOf("ProductSyncId" to product, "BatchNumber" to "L-0", "Quantity" to "0"))
        store.apply(StoreChanges(outboxClearedUpTo = Long.MAX_VALUE))
        val stamp = stampOf("Products", product)
        writes.closeBatch(batch)
        assertEquals(stamp, stampOf("Products", product))
        assertEquals(listOf("ProductBatch"), store.outbox().map { it.entityType })
        assertTrue(store.rows("ProductBatches").getValue(batch)["ClosedAtUtc"] != null)
        assertTrue(movements().isEmpty())
    }

    @Test
    fun cerrarUnLoteConSusPartesYaEscritasNoDescuentaOtraVez() {
        cloudPatient()
        linked()
        val batch = writes.registerStockEntry(product, "L-1", null, 2, null, "Entrada", "Dr.")
        val lot = io.github.marioalr.tpvca.sync.rules.SyncIds.derived("close:", batch)
        val rest = io.github.marioalr.tpvca.sync.rules.SyncIds.derived("close-rest:", batch)
        // Las dos partes de otro equipo ya llegaron y el lote todavía no
        localWriter.create("InventoryMovements", lot, mapOf("ProductSyncId" to product, "BatchSyncId" to batch, "MovementType" to "5", "Quantity" to "2", "Delta" to "-2", "Date" to "2020-01-02 10:00:00.0000000"))
        localWriter.create("InventoryMovements", rest, mapOf("ProductSyncId" to product, "MovementType" to "5", "Quantity" to "2", "Delta" to "2", "Date" to "2020-01-02 10:00:00.0000000"))
        store.apply(StoreChanges(outboxClearedUpTo = Long.MAX_VALUE))
        val stamp = stampOf("Products", product)
        val stock = store.rows("Products").getValue(product)["CurrentStock"]
        writes.closeBatch(batch)
        assertEquals(stamp, stampOf("Products", product))
        assertEquals(stock, store.rows("Products").getValue(product)["CurrentStock"])
        assertEquals(listOf("ProductBatch"), store.outbox().map { it.entityType })
    }

    @Test
    fun cerrarUnLoteSiEscribeTocaElProductoYPuedeDejarloEnNegativo() {
        cloudPatient()
        linked()
        val batch = writes.registerStockEntry(product, "L-1", null, 2, null, "Entrada", "Dr.")
        store.apply(StoreChanges(writes = listOf(RowWrite("Products", product, mapOf("CurrentStock" to 1L), isNew = false))))
        writes.closeBatch(batch)
        assertEquals(-1L, store.rows("Products").getValue(product)["CurrentStock"])
        assertTrue(store.outbox().any { it.entityType == "Product" })
    }

    @Test
    fun losAvisosDeLoQueLlegaSeQuedan() {
        cloudPatient()
        linked()
        val now = cloud.nowText()
        cloud.admin("INSERT INTO Patients (SyncId, UpdatedAtUtc, SyncedAtUtc, OriginDeviceId, FullName, Phone, IsActive) VALUES ('10000000-0000-4000-8000-0000000000d1', '2026-01-01 00:00:00.0000000', ?, 'pc', 'José Pérez', '5512345678', 1)", now)
        cloud.admin("INSERT INTO Patients (SyncId, UpdatedAtUtc, SyncedAtUtc, OriginDeviceId, FullName, Phone, IsActive) VALUES ('10000000-0000-4000-8000-0000000000d2', '2026-01-01 00:00:00.0000000', ?, 'pc', 'JOSE PEREZ', '55 1234 5678', 1)", now)
        for (id in listOf("e1", "e2")) {
            cloud.admin(
                "INSERT INTO Appointments (SyncId, UpdatedAtUtc, SyncedAtUtc, OriginDeviceId, PatientRecordSyncId, Date, Time, DurationMinutes, Status) VALUES (?, '2026-01-01 00:00:00.0000000', ?, 'pc', ?, '2030-01-10 00:00:00.0000000', '10:00', 30, 'Programada')",
                "10000000-0000-4000-8000-0000000000$id", now, patient,
            )
        }
        assertTrue(engine().sync(SyncTrigger.USER) is SyncOutcome.Completed)
        val notices = io.github.marioalr.tpvca.sync.engine.SyncNotices.read(store.meta(SyncEngine.NOTICES))
        assertEquals(
            setOf(
                io.github.marioalr.tpvca.sync.engine.SyncNotice("Phone", "10000000-0000-4000-8000-0000000000d1", "10000000-0000-4000-8000-0000000000d2"),
                io.github.marioalr.tpvca.sync.engine.SyncNotice(io.github.marioalr.tpvca.sync.engine.SyncNotices.OVERLAP, "10000000-0000-4000-8000-0000000000e1", "10000000-0000-4000-8000-0000000000e2"),
            ),
            notices,
        )
        // Otra bajada sin nada nuevo no los quita
        assertTrue(engine().sync(SyncTrigger.USER) is SyncOutcome.Completed)
        assertEquals(notices, io.github.marioalr.tpvca.sync.engine.SyncNotices.read(store.meta(SyncEngine.NOTICES)))
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
