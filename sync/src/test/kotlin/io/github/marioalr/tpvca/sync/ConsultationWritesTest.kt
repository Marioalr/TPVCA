package io.github.marioalr.tpvca.sync

import io.github.marioalr.tpvca.domain.devices.DeviceRole
import io.github.marioalr.tpvca.sync.engine.ConsultationNote
import io.github.marioalr.tpvca.sync.engine.FinishConsultation
import io.github.marioalr.tpvca.sync.engine.SaleLine
import io.github.marioalr.tpvca.sync.engine.StoreChanges
import io.github.marioalr.tpvca.sync.engine.SyncEngine
import io.github.marioalr.tpvca.sync.engine.SyncOutcome
import io.github.marioalr.tpvca.sync.engine.SyncTrigger
import io.github.marioalr.tpvca.sync.engine.WriteEngine
import io.github.marioalr.tpvca.sync.engine.WriteRefused
import io.github.marioalr.tpvca.sync.link.MemoryFolioNumbers
import io.github.marioalr.tpvca.sync.rules.PatientSheets
import io.github.marioalr.tpvca.sync.rules.SyncIds
import io.github.marioalr.tpvca.sync.rules.consultation.AccountLine
import io.github.marioalr.tpvca.sync.rules.consultation.ConsultationDraft
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDateTime

/** La consulta en curso y «Terminar y cobrar»: una sola transacción, con y sin conexión, contra una nube que ejecuta el SQL de verdad. */
class ConsultationWritesTest {
    private val cloud = SqlCloud()
    private val store = InMemorySyncStore()
    private val writes = WriteEngine(store, { "device-b" }, MemoryFolioNumbers(), { cloud.connection })

    private val patient = "10000000-0000-4000-8000-000000000001"
    private val product = "10000000-0000-4000-8000-000000000002"
    private val batchA = "10000000-0000-4000-8000-0000000000a1"
    private val batchB = "10000000-0000-4000-8000-0000000000a2"
    private val appointment = "10000000-0000-4000-8000-0000000000c1"

    private fun meta(key: String, value: String) = cloud.admin("INSERT INTO _tpvc_meta (Key, Value) VALUES (?, ?) ON CONFLICT(Key) DO UPDATE SET Value = excluded.Value", key, value)

    private fun link(role: DeviceRole = DeviceRole.AndroidTablet, code: String = "T1") {
        cloud.admin("INSERT INTO DeviceRegistry (DeviceId, DeviceCode, Role, DisplayName, Model, PairedAtUtc) VALUES ('device-b', ?, ?, 'b', '', 'x')", code, role.name)
        assertTrue(SyncEngine(cloud.connection, store, io.github.marioalr.tpvca.sync.engine.SyncEngineConfig("device-b")).sync(SyncTrigger.USER) is SyncOutcome.Completed)
    }

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
        val now = cloud.nowText()
        val old = "'2026-01-01 00:00:00.0000000'"
        cloud.admin("INSERT INTO Patients (SyncId, UpdatedAtUtc, SyncedAtUtc, OriginDeviceId, FullName, RecordNumber, IsActive, LastDiagnosis, ActivePrescriptionJson) VALUES (?, $old, ?, 'pc', 'Ana', 'EXP-2026-001', 1, 'Antes', ?)", patient, now, PREVIOUS)
        cloud.admin("INSERT INTO Categories (SyncId, UpdatedAtUtc, SyncedAtUtc, Name) VALUES ('10000000-0000-4000-8000-000000000003', $old, ?, 'C')", now)
        cloud.admin("INSERT INTO Products (SyncId, UpdatedAtUtc, SyncedAtUtc, Name, Code, Price, CurrentStock, IsActive, CategorySyncId) VALUES (?, $old, ?, 'Gasa', 'P1', '10.00', 5, 1, '10000000-0000-4000-8000-000000000003')", product, now)
        for ((id, quantity, day) in listOf(Triple(batchA, 2, "01"), Triple(batchB, 3, "02"))) {
            cloud.admin("INSERT INTO ProductBatches (SyncId, UpdatedAtUtc, SyncedAtUtc, ProductSyncId, BatchNumber, ExpirationDate, Quantity, IsDepleted) VALUES (?, $old, ?, ?, ?, '2030-01-$day 00:00:00.0000000', ?, 0)", id, now, product, "L-$day", quantity)
        }
        cloud.admin("INSERT INTO Appointments (SyncId, UpdatedAtUtc, SyncedAtUtc, OriginDeviceId, PatientRecordSyncId, Date, Time, DurationMinutes, Status) VALUES (?, $old, ?, 'pc', ?, '2030-01-10 00:00:00.0000000', '10:00', 30, 'Programada')", appointment, now, patient)
    }

    private fun draft(folio: String = "CNS-20261106-103000", patient: String = this.patient) = ConsultationDraft(
        patientSyncId = patient,
        patientName = "Ana",
        folio = folio,
        startedAt = "2026-11-06T10:30:00",
        items = listOf(AccountLine("producto", catalogSyncId = product, code = "P1", name = "Gasa", unitPrice = BigDecimal("10.00"), quantity = 3)),
        attendingAppointmentSyncId = appointment,
        chargeSaleSyncId = SyncIds.random(),
    )

    private fun request(d: ConsultationDraft, lines: List<SaleLine> = listOf(SaleLine(productSyncId = product, quantity = 3, unitPrice = BigDecimal("10.00"), code = "P1", description = "Gasa"))) = FinishConsultation(
        patientSyncId = d.patientSyncId,
        note = ConsultationNote(
            d.folio, LocalDateTime.of(2026, 11, 6, 10, 30), "Fiebre", "TA: 120/80 mmHg | Peso: 70 kg", "Normal", "", "Faringitis", "Reposo", "", "[]", false, "",
        ),
        lines = lines,
        saleSyncId = d.chargeSaleSyncId,
        patientName = "Ana",
        paymentMethod = "Efectivo",
        responsible = "Dra. Ejemplo",
        appointmentSyncId = d.attendingAppointmentSyncId,
        restorePrescriptionJson = null,
    )

    private fun rows(table: String) = store.rows(table)

    @Test
    fun terminarYCobrarEsTodoJuntoConSusEntradasDeLaCola() {
        link()
        val d = draft()
        assertNull(writes.startConsultation(d))
        assertNotNull(store.meta(WriteEngine.CONSULTATION_DRAFT))
        assertEquals("Empezar no anota nada para subir", 0, store.pendingChanges())

        val done = writes.finishConsultation(request(d))
        assertFalse(done.alreadyFinished)
        val sale = done.sale!!
        assertEquals(d.chargeSaleSyncId, sale.syncId)
        assertTrue(sale.folio, sale.folio.startsWith("TKT-") && "-T1-" !in sale.folio)
        val saleRow = rows("Sales").getValue(sale.syncId)
        assertEquals("El ticket dice el paciente, en las dos columnas como la PC", listOf("Ana", "Ana"), listOf(saleRow["CustomerName"], saleRow["PatientName"]))

        val note = rows("PatientConsultations").values.single()
        assertEquals(d.folio, note["Folio"])
        assertEquals("Faringitis", note["Diagnosis"])
        assertEquals("Los signos también como números", "120", note["BloodPressureSystolic"].toString().removeSuffix(".0"))
        assertEquals("70", note["Weight"].toString().removeSuffix(".0"))
        val p = rows("Patients").getValue(patient)
        assertEquals("Faringitis", p["LastDiagnosis"])
        assertEquals("Fiebre\n\nPlan: Reposo", p["ClinicalNotes"])
        assertNotNull("LastVisit es derivada y se recalcula", p["LastVisit"])
        assertEquals("Atendida", rows("Appointments").getValue(appointment)["Status"])
        assertEquals("El borrador se va en la misma transacción", null, store.meta(WriteEngine.CONSULTATION_DRAFT))

        val movements = rows("InventoryMovements").values.toList()
        assertEquals("Un movimiento por lote", setOf(batchA, batchB), movements.map { it["BatchSyncId"] }.toSet())
        assertEquals(listOf(-2L, -1L), movements.sortedBy { it["BatchSyncId"] as String }.map { it["Delta"] })
        assertEquals(2L, rows("Products").getValue(product)["CurrentStock"])

        val queued = store.outbox().map { it.entityType }.toSet()
        for (type in listOf("Sale", "SaleItem", "InventoryMovement", "PatientConsultation", "PatientRecord", "PatientAppointment", "Product", "ProductBatch")) {
            assertTrue("Falta $type en la cola: $queued", type in queued)
        }
    }

    @Test
    fun sinConexionElTicketYLaConsultaLlevanLaLetra() {
        link()
        cloud.offline = true
        val folio = writes.consultationFolio(online = false)
        assertTrue(folio, Regex("CNS-\\d{8}-T1-\\d{6}").matches(folio))
        assertTrue(Regex("CNS-\\d{8}-\\d{6}").matches(writes.consultationFolio(online = true)))
        val d = draft(folio)
        assertNull(writes.startConsultation(d))
        val sale = writes.finishConsultation(request(d)).sale!!
        assertTrue(sale.folio, Regex("TKT-\\d{8}-T1-0001").matches(sale.folio))
        assertEquals("Atendida", rows("Appointments").getValue(appointment)["Status"])
        assertTrue(store.pendingChanges() > 0)

        // Al volver la red, todo sube en una sincronización
        cloud.offline = false
        assertTrue(SyncEngine(cloud.connection, store, io.github.marioalr.tpvca.sync.engine.SyncEngineConfig("device-b")).sync(SyncTrigger.USER) is SyncOutcome.Completed)
        assertEquals(0, store.pendingChanges())
    }

    @Test
    fun siAlgoFallaNoQuedaNadaYLaConsultaSigue() {
        link()
        val d = draft()
        writes.startConsultation(d)
        // El expediente se dio de baja a media consulta: falla después de la nota y antes de la venta
        store.apply(StoreChanges(writes = listOf(io.github.marioalr.tpvca.sync.engine.RowWrite("Patients", patient, mapOf("IsActive" to 0L), isNew = false))))
        val failure = assertThrows(WriteRefused::class.java) { writes.finishConsultation(request(d)) }
        assertEquals(WriteRefused.Reason.CONFLICT, failure.reason)
        assertTrue(rows("PatientConsultations").isEmpty())
        assertTrue(rows("Sales").isEmpty())
        assertTrue(rows("InventoryMovements").isEmpty())
        assertEquals("Programada", rows("Appointments").getValue(appointment)["Status"])
        assertEquals(5L, rows("Products").getValue(product)["CurrentStock"])
        assertEquals(0, store.pendingChanges())
        assertNotNull(store.meta(WriteEngine.CONSULTATION_DRAFT))
    }

    @Test
    fun dosToquesNoCobranDosVeces() {
        link()
        val d = draft()
        writes.startConsultation(d)
        val first = writes.finishConsultation(request(d)).sale!!
        val second = writes.finishConsultation(request(d))
        assertTrue(second.alreadyFinished)
        assertEquals(first.folio, second.sale!!.folio)
        assertEquals(1, rows("Sales").size)
        assertEquals(1, rows("PatientConsultations").size)
        assertEquals(2L, rows("Products").getValue(product)["CurrentStock"])
    }

    @Test
    fun sinCargosNoHayVentaEnCeros() {
        link()
        val d = draft()
        writes.startConsultation(d)
        val done = writes.finishConsultation(request(d, emptyList()))
        assertNull(done.sale)
        assertTrue(rows("Sales").isEmpty())
        assertEquals(1, rows("PatientConsultations").size)
        assertEquals("Atendida", rows("Appointments").getValue(appointment)["Status"])
    }

    @Test
    fun sinExistenciasSeCobraIgualYAvisa() {
        link()
        val d = draft()
        writes.startConsultation(d)
        val sale = writes.finishConsultation(request(d, listOf(SaleLine(productSyncId = product, quantity = 7, unitPrice = BigDecimal("10.00"))))).sale!!
        assertEquals(-2L, rows("Products").getValue(product)["CurrentStock"])
        assertEquals("Gasa", sale.shortages.single().productName)
        assertEquals(5, sale.shortages.single().available)
        assertEquals(7, sale.shortages.single().charged)
    }

    @Test
    fun unaConsultaNoPisaAOtra() {
        link()
        val first = draft("CNS-20261106-100000")
        assertNull(writes.startConsultation(first))
        val other = draft("CNS-20261106-110000", "10000000-0000-4000-8000-000000000099")
        assertEquals(first.folio, writes.startConsultation(other)?.folio)
        assertFalse("Un guardado de otro folio no entra", writes.saveConsultation(other.copy(diagnosis = "x")))
        assertEquals(first.folio, ConsultationDraft.decode(store.meta(WriteEngine.CONSULTATION_DRAFT))?.folio)
    }

    @Test
    fun unGuardadoTardeNoRevivenLaConsulta() {
        link()
        val d = draft()
        writes.startConsultation(d)
        writes.finishConsultation(request(d))
        assertFalse(writes.saveConsultation(d.copy(diagnosis = "tarde")))
        assertNull(store.meta(WriteEngine.CONSULTATION_DRAFT))
    }

    @Test
    fun laHojaYElBorradorVanJuntos() {
        link()
        val d = draft()
        writes.startConsultation(d)
        val row = JsonObject(mapOf("RowId" to JsonPrimitive(SyncIds.random()), "SupplyName" to JsonPrimitive("Gasa"), "Quantity" to JsonPrimitive(1)))
        assertTrue(writes.saveConsultation(d.copy(notes = "con insumo"), mapOf(PatientSheets.SUPPLIES to { rows -> rows.map { it.raw } + row })))
        assertTrue((rows("Patients").getValue(patient)[PatientSheets.SUPPLIES] as String).contains("Gasa"))
        assertEquals("con insumo", ConsultationDraft.decode(store.meta(WriteEngine.CONSULTATION_DRAFT))?.notes)

        // Si la hoja no se puede escribir, tampoco cambia el borrador
        val ghost = d.copy(patientSyncId = "10000000-0000-4000-8000-000000000099", notes = "no")
        assertThrows(WriteRefused::class.java) { writes.saveConsultation(ghost, mapOf(PatientSheets.SUPPLIES to { emptyList() })) }
        assertEquals("con insumo", ConsultationDraft.decode(store.meta(WriteEngine.CONSULTATION_DRAFT))?.notes)
    }

    @Test
    fun descartarRegresaElExpedienteYNoCobra() {
        link()
        val d = draft()
        writes.startConsultation(d)
        writes.saveConsultation(d, mapOf(PatientSheets.PRESCRIPTION to { emptyList() }))
        assertEquals("[]", rows("Patients").getValue(patient)[PatientSheets.PRESCRIPTION])
        assertTrue(writes.discardConsultation(d, PREVIOUS))
        assertEquals(PREVIOUS, rows("Patients").getValue(patient)[PatientSheets.PRESCRIPTION])
        assertTrue(rows("PatientConsultations").isEmpty())
        assertTrue(rows("Sales").isEmpty())
        assertNull(store.meta(WriteEngine.CONSULTATION_DRAFT))
        assertEquals("Programada", rows("Appointments").getValue(appointment)["Status"])
        assertFalse("Dos veces no hace nada", writes.discardConsultation(d, PREVIOUS))
    }

    @Test
    fun unaConsultaEnOtroEquipoEsDeSoloLectura() {
        link()
        val d = draft().copy(handoff = JsonObject(mapOf("ToDeviceName" to JsonPrimitive("La PC"))))
        assertNull(writes.startConsultation(d))
        assertEquals(WriteRefused.Reason.CONFLICT, assertThrows(WriteRefused::class.java) { writes.saveConsultation(d, mapOf(PatientSheets.SUPPLIES to { emptyList() })) }.reason)
        assertEquals(WriteRefused.Reason.CONFLICT, assertThrows(WriteRefused::class.java) { writes.discardConsultation(d, null) }.reason)
        assertEquals(WriteRefused.Reason.CONFLICT, assertThrows(WriteRefused::class.java) { writes.finishConsultation(request(d)) }.reason)
        assertEquals(0, store.pendingChanges())
    }

    @Test
    fun elCelularNoEmpiezaNiCobra() {
        link(DeviceRole.AndroidMobileAdmin, "C1")
        assertEquals(WriteRefused.Reason.FORBIDDEN, assertThrows(WriteRefused::class.java) { writes.startConsultation(draft()) }.reason)
        assertEquals(WriteRefused.Reason.FORBIDDEN, assertThrows(WriteRefused::class.java) { writes.finishConsultation(request(draft())) }.reason)
        assertNull(store.meta(WriteEngine.CONSULTATION_DRAFT))
    }

    private companion object {
        const val PREVIOUS = """[{"RowId":"10000000-0000-4000-8000-0000000000e1","MedicineName":"Paracetamol 500 mg","Dosage":"1 tableta"}]"""
    }
}
