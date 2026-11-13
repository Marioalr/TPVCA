package io.github.marioalr.tpvca.sync

import io.github.marioalr.tpvca.domain.devices.DeviceRole
import io.github.marioalr.tpvca.sync.engine.SaleLine
import io.github.marioalr.tpvca.sync.engine.StoreChanges
import io.github.marioalr.tpvca.sync.engine.SyncEngine
import io.github.marioalr.tpvca.sync.engine.SyncEngineConfig
import io.github.marioalr.tpvca.sync.engine.SyncOutcome
import io.github.marioalr.tpvca.sync.engine.SyncTrigger
import io.github.marioalr.tpvca.sync.engine.WriteEngine
import io.github.marioalr.tpvca.sync.engine.WriteRefused
import io.github.marioalr.tpvca.sync.link.MemoryFolioNumbers
import io.github.marioalr.tpvca.sync.rules.consultation.AccountCatalog
import io.github.marioalr.tpvca.sync.rules.consultation.AccountLines
import io.github.marioalr.tpvca.sync.rules.consultation.ConsultationAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDateTime

/** El punto de venta de la tablet (K9) contra el motor de escritura: cobrar una sola vez, sin conexión con la letra, el borrador de la cuenta y cancelar por `SyncId`. */
class PointOfSaleWritesTest {
    private val cloud = SqlCloud()
    private val store = InMemorySyncStore()
    private val folios = MemoryFolioNumbers()
    private var online = true

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
        cloud.admin("INSERT INTO Categories (SyncId, UpdatedAtUtc, SyncedAtUtc, Name) VALUES (?, '2026-01-01 00:00:00.0000000', ?, 'C')", category, cloud.nowText())
        cloud.admin(
            "INSERT INTO Products (SyncId, UpdatedAtUtc, SyncedAtUtc, Name, Code, Price, CurrentStock, IsActive, CategorySyncId) VALUES (?, '2026-01-01 00:00:00.0000000', ?, 'Paracetamol', 'P1', '10', 1, 1, ?)",
            product, cloud.nowText(), category,
        )
        cloud.admin("INSERT INTO DeviceRegistry (DeviceId, DeviceCode, Role, DisplayName, Model, PairedAtUtc) VALUES ('device-b', 'T1', ?, 'b', '', 'x')", DeviceRole.AndroidTablet.name)
        assertTrue(SyncEngine(cloud.connection, store, SyncEngineConfig("device-b")).sync(SyncTrigger.USER) is SyncOutcome.Completed)
    }

    private val category = "10000000-0000-4000-8000-000000000003"
    private val product = "10000000-0000-4000-8000-000000000002"
    private val writes = WriteEngine(store, { "device-b" }, folios, { if (online) cloud.connection else null })

    private val localWriter = io.github.marioalr.tpvca.sync.engine.LocalWriter(store, { "device-b" })

    private fun stock() = store.rows("Products").getValue(product)["CurrentStock"]

    private fun line(quantity: Int = 1) = SaleLine(productSyncId = product, quantity = quantity, unitPrice = BigDecimal.TEN, code = "P1", description = "Paracetamol")

    @Test
    fun elMismoTicketNuncaSeCobraDosVeces() {
        val id = "20000000-0000-4000-8000-000000000001"
        val first = writes.registerSale(listOf(line(2)), saleSyncId = id)!!
        val folioTakes = cloud.executed.count { it.contains("FolioCounters") }
        val second = writes.registerSale(listOf(line(2)), saleSyncId = id)!!
        assertFalse(first.repeated)
        assertTrue("El segundo toque lo encuentra", second.repeated)
        assertEquals(first.folio, second.folio)
        assertEquals(1, store.rows("Sales").size)
        assertEquals(1, store.rows("SaleItems").size)
        assertEquals("Las existencias bajan una vez", -1L, stock())
        assertEquals("No gasta otro folio", folioTakes, cloud.executed.count { it.contains("FolioCounters") })
    }

    @Test
    fun laCuentaGuardadaSeVaConLaVentaEnLaMismaTransaccion() {
        store.apply(StoreChanges(meta = mapOf(WriteEngine.SALE_DRAFT to "{}")))
        writes.registerSale(listOf(line()), saleSyncId = "20000000-0000-4000-8000-000000000002", draftRemoved = WriteEngine.SALE_DRAFT)
        assertNull(store.meta(WriteEngine.SALE_DRAFT))
    }

    @Test
    fun unaVentaQueFallaDejaLaCuentaGuardada() {
        store.apply(StoreChanges(meta = mapOf(WriteEngine.SALE_DRAFT to "{}")))
        cloud.admin("UPDATE Products SET IsActive = 0")
        localWriter.edit("Products", product, mapOf("IsActive" to false))
        val refused = assertThrows(WriteRefused::class.java) {
            writes.registerSale(listOf(line()), saleSyncId = "20000000-0000-4000-8000-000000000003", draftRemoved = WriteEngine.SALE_DRAFT)
        }
        assertEquals("El producto 'Paracetamol' ya no existe en el inventario.", refused.message)
        assertEquals("{}", store.meta(WriteEngine.SALE_DRAFT))
        assertEquals(0, store.rows("Sales").size)
    }

    @Test
    fun sinConexionSeCobraConLaLetraYEnNegativo() {
        online = false
        val sale = writes.registerSale(listOf(line(3)), saleSyncId = "20000000-0000-4000-8000-000000000004")!!
        assertTrue(sale.folio, sale.folio.contains("-T1-"))
        assertEquals(-2L, stock())
        assertEquals("Lo que no alcanzó, avisado", 1, sale.shortages.size)
        online = true
        assertTrue(SyncEngine(cloud.connection, store, SyncEngineConfig("device-b")).sync(SyncTrigger.USER) is SyncOutcome.Completed)
        assertEquals(1, cloud.admin("SELECT SyncId FROM Sales WHERE Folio = ?", sale.folio).size)
    }

    @Test
    fun seCancelaPorSyncIdAunqueElFolioSeRepita() {
        val a = writes.registerSale(listOf(line()), saleSyncId = "20000000-0000-4000-8000-000000000005")!!
        val b = writes.registerSale(listOf(line()), saleSyncId = "20000000-0000-4000-8000-000000000006")!!
        // Dos equipos sin conexión con el mismo número: la regularización de la PC lo arregla después
        localWriter.edit("Sales", b.syncId, mapOf("Folio" to a.folio))
        assertTrue(writes.cancelSale(b.syncId))
        assertEquals(0L, store.rows("Sales").getValue(a.syncId)["IsCancelled"])
        assertEquals(1L, store.rows("Sales").getValue(b.syncId)["IsCancelled"])
        assertEquals("Solo regresó lo del segundo", 0L, stock())
        val again = assertThrows(WriteRefused::class.java) { writes.cancelSale(b.syncId) }
        assertEquals(WriteRefused.Reason.CONFLICT, again.reason)
        assertEquals(0L, stock())
    }

    @Test
    fun pasarLaVentaALaConsultaVaciaLaCuentaJunto() {
        // Sin consulta guardada: no se escribe nada y la cuenta se queda
        store.apply(StoreChanges(meta = mapOf(WriteEngine.SALE_DRAFT to "{}")))
        val draft = io.github.marioalr.tpvca.sync.rules.consultation.ConsultationDraft(folio = "CNS-1", patientSyncId = "p", patientName = "Ana", startedAt = "2026-11-13T10:00:00", chargeSaleSyncId = "20000000-0000-4000-8000-000000000009")
        assertFalse(writes.saveConsultation(draft, draftsRemoved = setOf(WriteEngine.SALE_DRAFT)))
        assertEquals("{}", store.meta(WriteEngine.SALE_DRAFT))
        store.apply(StoreChanges(meta = mapOf(WriteEngine.CONSULTATION_DRAFT to draft.encode())))
        assertTrue(writes.saveConsultation(draft, draftsRemoved = setOf(WriteEngine.SALE_DRAFT)))
        assertNull(store.meta(WriteEngine.SALE_DRAFT))
    }

    @Test
    fun loDelPuntoDeVentaSumaASuRenglonDeLaConsulta() {
        val account = ConsultationAccount(AccountCatalog(), now = { LocalDateTime.of(2026, 11, 13, 10, 0) })
        assertTrue(account.addPointOfSaleLine(AccountLines.PRODUCT, product, "P1", "Paracetamol", null, null, "Caja", BigDecimal.TEN, 2))
        assertTrue(account.addPointOfSaleLine(AccountLines.PRODUCT, product, "P1", "Paracetamol", null, null, "Caja", BigDecimal.TEN, 1))
        assertTrue("Las piezas sueltas van aparte", account.addPointOfSaleLine(AccountLines.PRODUCT, product, "P1", "Paracetamol", null, null, "Piezas ind.", BigDecimal.ONE, 3, isIndividualUnits = true))
        assertTrue(account.addPointOfSaleLine(AccountLines.SERVICE, null, "SERV-001", "Consulta", null, null, "Servicio", BigDecimal.TEN, 1))
        assertFalse("Sin piezas no agrega", account.addPointOfSaleLine(AccountLines.PRODUCT, product, "P1", "Paracetamol", null, null, "Caja", BigDecimal.TEN, 0))
        assertEquals(listOf(3, 3, 1), account.lines.map { it.quantity })
        assertEquals(listOf(false, true, false), account.lines.map { it.isIndividualUnits })
        account.readOnly = true
        assertFalse("Enviada a otro equipo, no", account.addPointOfSaleLine(AccountLines.PRODUCT, product, "P1", "Paracetamol", null, null, "Caja", BigDecimal.TEN, 1))
    }

    /** «Agregar a la consulta de…» del punto de venta: cada insumo con sus piezas (0 no se carga), en piezas sueltas a su precio por pieza. */
    @Test
    fun unServicioDelPuntoDeVentaConSusPiezasTalCual() {
        val gasa = io.github.marioalr.tpvca.domain.model.Product(id = 1, code = "G1", name = "Gasa", unitsPerPackage = 5, price = BigDecimal("10.00"), syncId = "g")
        val jeringa = io.github.marioalr.tpvca.domain.model.Product(id = 2, code = "J1", name = "Jeringa", price = BigDecimal("12.00"), syncId = "j")
        val service = io.github.marioalr.tpvca.domain.model.ClinicalService(
            id = 3, code = "S1", name = "Curación", category = "Curaciones", price = BigDecimal("250.00"), syncId = "s", estimatedDurationMinutes = 15,
            defaultProducts = listOf(io.github.marioalr.tpvca.domain.model.ServiceDefaultProduct(gasa, 2), io.github.marioalr.tpvca.domain.model.ServiceDefaultProduct(jeringa, 0)),
        )
        val account = ConsultationAccount(AccountCatalog(), now = { LocalDateTime.of(2026, 11, 13, 10, 0) })
        assertTrue(account.addPointOfSaleService(service))
        assertEquals(listOf("Curación", "Gasa"), account.lines.map { it.name })
        val fee = account.lines[0]
        assertEquals("Servicio Clínico • Curaciones", fee.genericName)
        assertEquals("Honorario • 15 min", fee.presentation)
        val pieces = account.lines[1]
        assertEquals(2, pieces.quantity)
        assertTrue(pieces.isIndividualUnits)
        assertEquals(BigDecimal("2.00"), pieces.unitPrice)
        account.readOnly = true
        assertFalse(account.addPointOfSaleService(service))
    }
}
