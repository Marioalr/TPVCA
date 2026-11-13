package io.github.marioalr.tpvca.sync

import io.github.marioalr.tpvca.domain.business.CatalogDuplicateRules
import io.github.marioalr.tpvca.domain.devices.DeviceRole
import io.github.marioalr.tpvca.sync.engine.LocalSide
import io.github.marioalr.tpvca.sync.engine.LocalWriter
import io.github.marioalr.tpvca.sync.engine.NoticeKind
import io.github.marioalr.tpvca.sync.engine.ServiceSupply
import io.github.marioalr.tpvca.sync.engine.ServiceValues
import io.github.marioalr.tpvca.sync.engine.StoreChanges
import io.github.marioalr.tpvca.sync.engine.SyncEngine
import io.github.marioalr.tpvca.sync.engine.SyncEngineConfig
import io.github.marioalr.tpvca.sync.engine.SyncNotice
import io.github.marioalr.tpvca.sync.engine.SyncNotices
import io.github.marioalr.tpvca.sync.engine.SyncOutcome
import io.github.marioalr.tpvca.sync.engine.SyncTrigger
import io.github.marioalr.tpvca.sync.engine.WriteEngine
import io.github.marioalr.tpvca.sync.engine.WriteRefused
import io.github.marioalr.tpvca.sync.link.MemoryFolioNumbers
import io.github.marioalr.tpvca.sync.rules.SyncIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Configuración → Servicios de la tablet (K9b) contra el motor de escritura: el servicio y sus insumos en una transacción con su entrada en la cola, los insumos guardados por producto (cada renglón
 * conserva su `SyncId`, como `SaveClinicalServiceAsync`), la revisión del código, «Desactivar» y «Eliminar» con sus lápidas; y el aviso del catálogo repetido después de cada bajada.
 */
class ServicesWritesTest {
    private val cloud = SqlCloud()
    private val store = InMemorySyncStore()
    private val writes = WriteEngine(store, { "device-b" }, MemoryFolioNumbers(), { null })
    private val category = "10000000-0000-4000-8000-000000000003"
    private val gauze = "10000000-0000-4000-8000-000000000002"
    private val syringe = "10000000-0000-4000-8000-000000000004"

    private fun meta(key: String, value: String) = cloud.admin("INSERT INTO _tpvc_meta (Key, Value) VALUES (?, ?) ON CONFLICT(Key) DO UPDATE SET Value = excluded.Value", key, value)

    private fun engine() = SyncEngine(cloud.connection, store, SyncEngineConfig("device-b"))

    private fun cloudProduct(id: String, code: String, name: String, stamp: String = "2026-01-01 00:00:00.0000000") = cloud.admin(
        "INSERT INTO Products (SyncId, UpdatedAtUtc, SyncedAtUtc, Name, Code, Price, UnitCost, CurrentStock, MinStock, UnitsPerPackage, OpenedUnitsRemaining, IsActive, CategorySyncId) " +
            "VALUES (?, ?, ?, ?, ?, '10', '4.00', 3, 5, 1, 0, 1, ?)",
        id, stamp, cloud.nowText(), name, code, category,
    )

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
        cloud.admin("INSERT INTO Categories (SyncId, UpdatedAtUtc, SyncedAtUtc, Name) VALUES (?, '2026-01-01 00:00:00.0000000', ?, 'Material de curación')", category, cloud.nowText())
        cloudProduct(gauze, "GAS-1", "Gasa estéril")
        cloudProduct(syringe, "JER-5", "Jeringa 5 ml")
        cloud.admin("INSERT INTO DeviceRegistry (DeviceId, DeviceCode, Role, DisplayName, Model, PairedAtUtc) VALUES ('device-b', 'T1', ?, 'b', '', 'x')", DeviceRole.AndroidTablet.name)
        assertTrue(engine().sync(SyncTrigger.USER) is SyncOutcome.Completed)
        clearOutbox()
    }

    private fun clearOutbox() = store.apply(StoreChanges(outboxClearedUpTo = Long.MAX_VALUE))

    private fun outbox() = store.outbox().map { it.entityType to it.syncId }

    private fun defaultsOf(service: String) = store.rows("ServiceDefaultProducts").values.filter { it["ClinicalServiceSyncId"] == service }

    private fun values(code: String = "SERV-006", vararg supplies: ServiceSupply) = ServiceValues(
        code = code, name = "Curación de herida", category = "Curaciones", price = BigDecimal("250.50"), estimatedDurationMinutes = 20, description = "Aseo y vendaje", isActive = true,
        supplies = supplies.toList(),
    )

    @Test
    fun unAltaGuardaElServicioYSusInsumosJuntosYNoSeRepiteConOtroToque() {
        val id = SyncIds.random()
        assertTrue(writes.saveService(values("serv-006", ServiceSupply(gauze, 2), ServiceSupply(syringe, 0), ServiceSupply(gauze, 3)), id, isNew = true))
        val row = store.rows("ClinicalServices").getValue(id)
        assertEquals("serv-006", row["Code"])
        assertEquals("250.50", row["Price"])
        assertEquals(20L, row["EstimatedDurationMinutes"])
        assertEquals(1L, row["IsActive"])
        // Por producto, el último que se agregó; al menos una pieza
        val defaults = defaultsOf(id).associate { it["ProductSyncId"] to it["Quantity"] }
        assertEquals(mapOf(gauze to 3L, syringe to 1L), defaults)
        assertEquals(listOf("ClinicalService" to id) + defaultsOf(id).map { "ServiceDefaultProduct" to it["SyncId"] }.sortedBy { it.second as String }, outbox().take(1) + outbox().drop(1).sortedBy { it.second })
        // Un segundo toque con el mismo SyncId no da de alta otro
        assertFalse(writes.saveService(values("serv-006"), id, isNew = true))
        assertEquals(1, store.rows("ClinicalServices").size)
    }

    @Test
    fun editarGuardaLosInsumosPorProductoConSuSyncIdYLapidaLoQuitado() {
        val id = SyncIds.random()
        writes.saveService(values("SERV-006", ServiceSupply(gauze, 2), ServiceSupply(syringe, 1)), id, isNew = true)
        val gauzeRow = defaultsOf(id).single { it["ProductSyncId"] == gauze }["SyncId"] as String
        val syringeRow = defaultsOf(id).single { it["ProductSyncId"] == syringe }["SyncId"] as String
        clearOutbox()
        // Sin cambios: nada sube
        writes.saveService(values("SERV-006", ServiceSupply(gauze, 2), ServiceSupply(syringe, 1)), id, isNew = false)
        assertTrue(outbox().isEmpty())
        // Otra cantidad de gasas y sin jeringa: el renglón de las gasas es el mismo, el de la jeringa deja su lápida
        writes.saveService(values("SERV-006", ServiceSupply(gauze, 4, "Dobladas")), id, isNew = false)
        val left = defaultsOf(id)
        assertEquals(listOf(gauzeRow), left.map { it["SyncId"] })
        assertEquals(4L, left.single()["Quantity"])
        assertEquals("Dobladas", left.single()["Instructions"])
        assertTrue(store.tombstones.values.any { it.entityType == "ServiceDefaultProduct" && it.syncId == syringeRow })
        assertEquals(setOf("ServiceDefaultProduct" to gauzeRow, "ServiceDefaultProduct" to syringeRow), outbox().toSet())
    }

    @Test
    fun elCodigoSeRevisaSoloEntreLosActivosYSoloSiCambia() {
        val first = SyncIds.random()
        writes.saveService(values("SERV-006"), first, isNew = true)
        val error = assertThrows(WriteRefused::class.java) { writes.saveService(values(" serv-006 "), SyncIds.random(), isNew = true) }
        assertEquals("El código serv-006 ya lo usa otro servicio.", error.message)
        // Uno desactivado no cuenta
        writes.setServiceActive(first, false)
        assertTrue(writes.saveService(values("SERV-006"), SyncIds.random(), isNew = true))
        // Un par repetido (llegó de otro equipo) se sigue pudiendo editar sin cambiarle el código
        writes.setServiceActive(first, true)
        assertTrue(writes.saveService(values("SERV-006").copy(price = BigDecimal("300")), first, isNew = false))
        assertTrue(writes.isServiceCodeTaken("SERV-006"))
        assertFalse(writes.isServiceCodeTaken("SERV-006", first))
    }

    @Test
    fun desactivarSoloCambiaActivoYEliminarSeLlevaSusInsumos() {
        val id = SyncIds.random()
        writes.saveService(values("SERV-006", ServiceSupply(gauze, 2)), id, isNew = true)
        val supply = defaultsOf(id).single()["SyncId"] as String
        clearOutbox()
        assertTrue(writes.setServiceActive(id, false))
        assertEquals(0L, store.rows("ClinicalServices").getValue(id)["IsActive"])
        assertEquals(listOf("ClinicalService" to id), outbox())
        assertEquals(1, defaultsOf(id).size)
        clearOutbox()
        assertTrue(writes.deleteService(id))
        assertTrue(id !in store.rows("ClinicalServices"))
        assertTrue(defaultsOf(id).isEmpty())
        assertEquals(setOf("ClinicalService" to id, "ServiceDefaultProduct" to supply), store.tombstones.values.map { it.entityType to it.syncId }.toSet())
        assertFalse("Ya no existe", writes.deleteService(id))
        assertThrows(WriteRefused::class.java) { writes.setServiceActive(id, true) }
        assertThrows(WriteRefused::class.java) { writes.saveService(values("SERV-007"), id, isNew = false) }
    }

    @Test
    fun unInsumoQueYaNoEstaNoSeGuardaYNadaQuedaAMedias() {
        val error = assertThrows(WriteRefused::class.java) { writes.saveService(values("SERV-006", ServiceSupply(gauze, 1), ServiceSupply(SyncIds.random(), 1)), SyncIds.random(), isNew = true) }
        assertEquals("Uno de los insumos ya no existe en este equipo.", error.message)
        assertTrue(store.rows("ClinicalServices").isEmpty())
        assertTrue(outbox().isEmpty())
    }

    @Test
    fun elCelularNoEditaServicios() {
        store.apply(StoreChanges(meta = mapOf(SyncEngine.DEVICE_ROLE to DeviceRole.AndroidMobileAdmin.name)))
        assertEquals(WriteRefused.Reason.FORBIDDEN, assertThrows(WriteRefused::class.java) { writes.saveService(values(), SyncIds.random(), isNew = true) }.reason)
        assertEquals(WriteRefused.Reason.FORBIDDEN, assertThrows(WriteRefused::class.java) { writes.deleteService(SyncIds.random()) }.reason)
        assertEquals(WriteRefused.Reason.FORBIDDEN, assertThrows(WriteRefused::class.java) { writes.setServiceActive(SyncIds.random(), false) }.reason)
    }

    // ------------------------------------------------------------------ el aviso del catálogo después de cada bajada

    private fun catalogNotices() = SyncNotices.read(store.meta(SyncEngine.NOTICES)).filter { it.kind.startsWith(SyncNotices.CATALOG_PREFIX) }

    private fun sync() = assertTrue(engine().sync(SyncTrigger.USER) is SyncOutcome.Completed)

    @Test
    fun loQueLlegaRepetidoAvisaYUnCambioDePrecioNo() {
        // Este equipo dio de alta «Gasa estéril» con otro código sin ver la de la PC: un par de aquí no avisa
        val local = SyncIds.random()
        LocalWriter(store, { "device-b" }).create("Products", local, mapOf("Code" to "GAS-2", "Name" to "Gasa estéril", "IsActive" to true, "CategorySyncId" to category))
        sync()
        assertTrue("Lo de aquí no se revisa al subir", catalogNotices().isEmpty())
        // Otro equipo cambia el precio de la gasa: no cuenta como llegada
        cloud.admin("UPDATE Products SET Price = '12', UpdatedAtUtc = '2026-02-01 00:00:00.0000000', SyncedAtUtc = ? WHERE SyncId = ?", cloud.nowText(), gauze)
        sync()
        assertEquals("12", store.rows("Products").getValue(gauze)["Price"])
        assertTrue(catalogNotices().isEmpty())
        // Otro equipo le cambia el código: ahora sí se compara, con todo el catálogo de aquí
        cloud.admin("UPDATE Products SET Code = 'GAS-3', UpdatedAtUtc = '2026-02-02 00:00:00.0000000', SyncedAtUtc = ? WHERE SyncId = ?", cloud.nowText(), gauze)
        sync()
        val kind = SyncNotices.catalogKind(CatalogDuplicateRules.Kind.Product)
        assertEquals(listOf(SyncNotice(kind, listOf(gauze, local).min(), listOf(gauze, local).max())), catalogNotices())
        val shown = LocalSide(store).notices(LocalDate.of(2026, 11, 13)).catalog.single()
        assertEquals(CatalogDuplicateRules.Match.Name, shown.match)
        // «Entendido»: el mismo par no vuelve aunque vuelva a llegar
        LocalSide(store).dismiss(NoticeKind.DuplicateCatalog)
        cloud.admin("UPDATE Products SET Code = 'GAS-4', UpdatedAtUtc = '2026-02-03 00:00:00.0000000', SyncedAtUtc = ? WHERE SyncId = ?", cloud.nowText(), gauze)
        sync()
        assertTrue(catalogNotices().isEmpty())
    }

    @Test
    fun unServicioYUnaCategoriaQueLleganRepetidosTambienAvisan() {
        val mine = SyncIds.random()
        writes.saveService(values("SERV-006"), mine, isNew = true)
        val theirs = SyncIds.random()
        cloud.admin(
            "INSERT INTO ClinicalServices (SyncId, UpdatedAtUtc, SyncedAtUtc, Code, Name, Category, Price, EstimatedDurationMinutes, IsActive, Description, CreatedAt) " +
                "VALUES (?, '2026-03-01 00:00:00.0000000', ?, 'SERV-006', 'Inyección', 'Procedimientos', '80', 10, 1, '', '2026-03-01 00:00:00.0000000')",
            theirs, cloud.nowText(),
        )
        val otherCategory = SyncIds.random()
        cloud.admin("INSERT INTO Categories (SyncId, UpdatedAtUtc, SyncedAtUtc, Name) VALUES (?, '2026-03-01 00:00:00.0000000', ?, 'material de curacion')", otherCategory, cloud.nowText())
        sync()
        val kinds = catalogNotices().map { it.kind }.toSet()
        assertEquals(setOf(SyncNotices.catalogKind(CatalogDuplicateRules.Kind.Service), SyncNotices.catalogKind(CatalogDuplicateRules.Kind.Category)), kinds)
    }

    @Test
    fun laPrimeraDescargaNoAvisa() {
        val fresh = InMemorySyncStore()
        cloudProduct(SyncIds.random(), "GAS-1", "Gasa estéril")
        assertTrue(SyncEngine(cloud.connection, fresh, SyncEngineConfig("device-b")).sync(SyncTrigger.USER) is SyncOutcome.Completed)
        assertTrue(SyncNotices.read(fresh.meta(SyncEngine.NOTICES)).isEmpty())
    }
}
