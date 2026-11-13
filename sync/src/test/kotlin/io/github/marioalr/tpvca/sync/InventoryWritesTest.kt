package io.github.marioalr.tpvca.sync

import io.github.marioalr.tpvca.domain.business.SupplierReceipt
import io.github.marioalr.tpvca.domain.business.SupplierReceiptLine
import io.github.marioalr.tpvca.domain.devices.DeviceRole
import io.github.marioalr.tpvca.sync.engine.ProductValues
import io.github.marioalr.tpvca.sync.engine.StoreChanges
import io.github.marioalr.tpvca.sync.engine.SyncEngine
import io.github.marioalr.tpvca.sync.engine.SyncEngineConfig
import io.github.marioalr.tpvca.sync.engine.SyncOutcome
import io.github.marioalr.tpvca.sync.engine.SyncTrigger
import io.github.marioalr.tpvca.sync.engine.WriteEngine
import io.github.marioalr.tpvca.sync.engine.WriteRefused
import io.github.marioalr.tpvca.sync.link.MemoryFolioNumbers
import io.github.marioalr.tpvca.sync.rules.SyncIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Productos, lotes y movimientos de la tablet (K9b) contra el motor de escritura: cada escritura con su entrada en la cola y en una transacción, las mismas filas que escribe `InventoryService` de la PC,
 * y el celular sin poder escribir nada de esto (K4b, hasta el 8.20).
 */
class InventoryWritesTest {
    private val cloud = SqlCloud()
    private val store = InMemorySyncStore()
    private val writes = WriteEngine(store, { "device-b" }, MemoryFolioNumbers(), { null })
    private val category = "10000000-0000-4000-8000-000000000003"
    private val product = "10000000-0000-4000-8000-000000000002"

    private fun meta(key: String, value: String) = cloud.admin("INSERT INTO _tpvc_meta (Key, Value) VALUES (?, ?) ON CONFLICT(Key) DO UPDATE SET Value = excluded.Value", key, value)

    private fun engine() = SyncEngine(cloud.connection, store, SyncEngineConfig("device-b"))

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
        cloud.admin("INSERT INTO Categories (SyncId, UpdatedAtUtc, SyncedAtUtc, Name) VALUES (?, '2026-01-01 00:00:00.0000000', ?, 'Medicamentos')", category, cloud.nowText())
        cloud.admin(
            "INSERT INTO Products (SyncId, UpdatedAtUtc, SyncedAtUtc, Name, Code, Price, UnitCost, CurrentStock, MinStock, UnitsPerPackage, OpenedUnitsRemaining, IsActive, CategorySyncId) " +
                "VALUES (?, '2026-01-01 00:00:00.0000000', ?, 'Paracetamol', 'P1', '10', '4.00', 3, 5, 1, 0, 1, ?)",
            product, cloud.nowText(), category,
        )
        cloud.admin("INSERT INTO DeviceRegistry (DeviceId, DeviceCode, Role, DisplayName, Model, PairedAtUtc) VALUES ('device-b', 'T1', ?, 'b', '', 'x')", DeviceRole.AndroidTablet.name)
        assertTrue(engine().sync(SyncTrigger.USER) is SyncOutcome.Completed)
    }

    private fun row(table: String, id: String) = store.rows(table).getValue(id)

    private fun movements() = store.rows("InventoryMovements").values.toList()

    private fun outboxTypes() = store.outbox().map { it.entityType }

    private fun clearOutbox() = store.apply(StoreChanges(outboxClearedUpTo = Long.MAX_VALUE))

    private fun values(code: String = "MED-9", stock: Int = 0, units: Int = 1) = ProductValues(
        code = code, name = "Ketorolaco 10 mg", genericName = " Ketorolaco ", presentation = "", categorySyncId = category, unit = "Cajas", price = BigDecimal("35.50"),
        unitCost = BigDecimal("20.00"), costExcludesVat = false, currentStock = stock, minStock = 5, optimalStock = 20, unitsPerPackage = units, location = "Estante A1",
        supplierCode = null, requiresRefrigeration = true, notes = null,
    )

    @Test
    fun unAltaConExistenciasDejaSuMovimientoInicialYNoSeRepiteConOtroToque() {
        clearOutbox()
        val id = SyncIds.random()
        assertTrue(writes.saveProduct(values(stock = 7), id, isNew = true, originalStock = null))
        val saved = row("Products", id)
        assertEquals("MED-9", saved["Code"])
        assertEquals("Ketorolaco", saved["GenericName"])
        assertNull("Vacío se guarda como nada", saved["Presentation"])
        assertEquals(7L, saved["CurrentStock"])
        assertEquals(1L, saved["IsActive"])
        val initial = movements().single { it["ProductSyncId"] == id }
        assertEquals(1L, initial["MovementType"]) // EntradaAjuste
        assertEquals(7L, initial["Delta"])
        assertEquals(0L, initial["StockBefore"])
        assertEquals(7L, initial["StockAfter"])
        assertEquals("Inventario inicial de alta de producto", initial["Reason"])
        assertEquals(listOf("Product", "InventoryMovement"), outboxTypes())
        // El segundo toque encuentra el alta ya guardada
        assertFalse(writes.saveProduct(values(stock = 7), id, isNew = true, originalStock = null))
        assertEquals(1, movements().count { it["ProductSyncId"] == id })
        assertEquals(2, store.outbox().size)
    }

    @Test
    fun elCodigoSeRevisaSoloAlDarDeAltaOCambiarlo() {
        assertTrue(writes.isProductCodeTaken(" p1 "))
        assertFalse("El suyo no cuenta", writes.isProductCodeTaken("P1", product))
        val error = assertThrows(WriteRefused::class.java) { writes.saveProduct(values(code = " p1"), SyncIds.random(), isNew = true, originalStock = null) }
        assertEquals("El código 'p1' ya está registrado en el inventario. Debe ser único.", error.message)
        // Un par repetido que llegó de otro equipo se sigue pudiendo editar sin cambiar el código
        val twin = SyncIds.random()
        store.apply(StoreChanges(writes = listOf(io.github.marioalr.tpvca.sync.engine.RowWrite("Products", twin, io.github.marioalr.tpvca.sync.engine.LocalWriter(store, { "x" }).buildRow(io.github.marioalr.tpvca.sync.engine.LocalWriter(store, { "x" }).table("Products"), twin, mapOf("Code" to "P1", "Name" to "Otro", "IsActive" to true, "CategorySyncId" to category), "2026-01-01 00:00:00.0000000"), isNew = true))))
        assertTrue(writes.saveProduct(values(code = "P1"), twin, isNew = false, originalStock = 0))
        assertThrows(WriteRefused::class.java) { writes.saveProduct(values(code = "MED-X").copy(code = "P1 "), SyncIds.random(), isNew = true, originalStock = null) }
    }

    @Test
    fun editarSinTocarLasExistenciasNoRevierteLoQueSeVendio() {
        clearOutbox()
        // El formulario se abrió con 3 y mientras tanto se vendieron 2 (aquí, la existencia guardada baja a 1)
        store.apply(StoreChanges(writes = listOf(io.github.marioalr.tpvca.sync.engine.RowWrite("Products", product, mapOf("CurrentStock" to 1L), isNew = false))))
        assertTrue(writes.saveProduct(values(code = "P1", stock = 3), product, isNew = false, originalStock = 3))
        assertEquals(1L, row("Products", product)["CurrentStock"])
        assertTrue(movements().isEmpty())
        assertEquals(BigDecimal("35.50"), BigDecimal(row("Products", product)["Price"] as String))
        // Un conteo: el médico escribió 10
        assertTrue(writes.saveProduct(values(code = "P1", stock = 10), product, isNew = false, originalStock = 3))
        val adjustment = movements().single()
        assertEquals(5L, adjustment["MovementType"]) // AjusteInventario
        assertEquals(9L, adjustment["Delta"])
        assertEquals(1L, adjustment["StockBefore"])
        assertEquals(10L, adjustment["StockAfter"])
        assertNull("Sin lote", adjustment["BatchSyncId"])
        assertEquals("Ajuste manual de existencias desde la ficha del producto", adjustment["Reason"])
        assertEquals(10L, row("Products", product)["CurrentStock"])
    }

    @Test
    fun unProductoQueYaNoExisteNoSeVuelveACrearAlEditarlo() {
        val error = assertThrows(WriteRefused::class.java) { writes.saveProduct(values(), SyncIds.random(), isNew = false, originalStock = 0) }
        assertEquals("El producto ya no existe en la base de datos.", error.message)
        assertEquals(1, store.rows("Products").size)
    }

    @Test
    fun darDeBajaSoloLoDesactiva() {
        clearOutbox()
        assertTrue(writes.deactivateProduct(product))
        assertEquals(0L, row("Products", product)["IsActive"])
        assertEquals(listOf("Product"), outboxTypes())
        assertFalse(writes.deactivateProduct("no-existe"))
    }

    @Test
    fun unConsumoSinCobroPasaAunqueNoAlcanceYSaleDeLosLotesPrimero() {
        val lot = SyncIds.random()
        assertTrue(writes.receiveBatch(lot, product, "L-1", LocalDate.of(2030, 1, 31), 2, null, 1))
        clearOutbox()
        // 3 + 2 del lote = 5; se consumen 7
        val shortage = writes.registerConsumption(product, 7, "  ")
        assertNotNull(shortage)
        assertEquals(5, shortage!!.available)
        assertEquals(7, shortage.charged)
        assertEquals(-2L, row("Products", product)["CurrentStock"])
        val exits = movements().filter { it["MovementType"] == 2L }
        assertEquals(listOf(-2L, -5L), exits.map { it["Delta"] }.sortedBy { -(it as Long) })
        assertEquals(lot, exits.single { it["Delta"] == -2L }["BatchSyncId"])
        assertTrue(exits.all { it["Reason"] == "Uso / aplicación durante consulta médica" && it["UnitCost"] == "4.00" })
        assertEquals(0L, row("ProductBatches", lot)["Quantity"])
        assertEquals(1L, row("ProductBatches", lot)["IsDepleted"])
        assertEquals(listOf("InventoryMovement", "InventoryMovement", "Product", "ProductBatch"), outboxTypes())
    }

    @Test
    fun unaEntradaCambiaElContenidoPorPaqueteEnLaMismaTransaccion() {
        clearOutbox()
        assertTrue(writes.registerEntry(product, 5, "Adquisición de insumos", 6))
        val p = row("Products", product)
        assertEquals(8L, p["CurrentStock"])
        assertEquals(6L, p["UnitsPerPackage"])
        val entry = movements().single()
        assertEquals(0L, entry["MovementType"]) // EntradaCompra
        assertEquals("4.00", entry["UnitCost"])
        assertNull(entry["BatchSyncId"])
        assertEquals(listOf("InventoryMovement", "Product"), outboxTypes())
        // Sube a la nube con el contenido por paquete
        assertTrue(engine().sync(SyncTrigger.USER) is SyncOutcome.Completed)
        assertEquals(0, store.pendingChanges())
        assertEquals(io.github.marioalr.tpvca.sync.hrana.HranaValue.Integer(6L), cloud.admin("SELECT UnitsPerPackage FROM Products WHERE SyncId = ?", product).single()["UnitsPerPackage"])
    }

    @Test
    fun registrarUnLoteUnaSolaVezAunqueSeToqueDosVeces() {
        val lot = SyncIds.random()
        assertTrue(writes.receiveBatch(lot, product, " L-7 ", LocalDate.of(2027, 3, 31), 4, "Factura 12", 1))
        assertFalse(writes.receiveBatch(lot, product, " L-7 ", LocalDate.of(2027, 3, 31), 4, "Factura 12", 1))
        val batch = row("ProductBatches", lot)
        assertEquals("L-7", batch["BatchNumber"])
        assertEquals("Factura 12", batch["Notes"])
        assertEquals(4L, batch["Quantity"])
        assertEquals(7L, row("Products", product)["CurrentStock"])
        val entry = movements().single()
        assertEquals("Entrada de lote L-7", entry["Reason"])
        assertEquals(lot, entry["BatchSyncId"])
        assertFalse(writes.isBatchClosedElsewhere(lot))
        // Llega el cierre de otro equipo antes que el lote cerrado
        io.github.marioalr.tpvca.sync.engine.LocalWriter(store, { "otro" }).create(
            "InventoryMovements", SyncIds.derived("close:", lot), mapOf("ProductSyncId" to product, "BatchSyncId" to lot, "MovementType" to "5", "Quantity" to "4", "Delta" to "-4", "Date" to "2026-01-02 10:00:00.0000000"),
        )
        assertTrue(writes.isBatchClosedElsewhere(lot))
        assertTrue("Cerrarlo avisa y no descuenta otra vez", writes.closeBatch(lot))
        assertEquals(7L, row("Products", product)["CurrentStock"])
        assertFalse("Ya cerrado aquí", writes.isBatchClosedElsewhere(lot))
    }

    @Test
    fun unPedidoEntraCompletoConProductosNuevosLotesYKardex() {
        clearOutbox()
        val result = writes.receiveSupplierOrder(
            SupplierReceipt(
                "LUIN", "5175", category,
                listOf(
                    SupplierReceiptLine(product, "WAN014", "VALCLAN 875/125MG C/14 TABS", 2, BigDecimal("72.51"), "L2401", LocalDate.of(2027, 11, 30)),
                    SupplierReceiptLine(null, "P1", "BUTIMAXIL 500MG C/20 CAPS", 1, BigDecimal("50.00")),
                    SupplierReceiptLine(null, "p1", "BUTIMAXIL 500MG C/20 CAPS", 2, BigDecimal("50.00"), unitsPerPackage = 20),
                ),
            ),
        )
        assertEquals(3, result.received)
        assertEquals(listOf("BUTIMAXIL 500MG C/20 CAPS"), result.createdWithoutPrice)
        val valclan = row("Products", product)
        assertEquals(5L, valclan["CurrentStock"])
        assertEquals("72.51", valclan["UnitCost"])
        assertEquals("WAN014", valclan["SupplierCode"])
        val created = store.rows("Products").values.single { it["SyncId"] != product }
        assertEquals("El SKU ya lo usa otro producto", "P1-2", created["Code"])
        assertEquals("Caja con 20 cápsulas", created["Presentation"])
        assertEquals("Cajas", created["Unit"])
        assertEquals(3L, created["CurrentStock"])
        assertEquals(20L, created["UnitsPerPackage"])
        assertEquals(0L, created["MinStock"])
        assertEquals(category, created["CategorySyncId"])
        val batch = store.rows("ProductBatches").values.single()
        assertEquals("L2401", batch["BatchNumber"])
        assertEquals("Pedido LUIN #5175", batch["Notes"])
        val entries = movements().filter { it["Reason"] == "Pedido LUIN #5175" }
        assertEquals(3, entries.size)
        assertEquals(batch["SyncId"], entries.single { it["ProductSyncId"] == product }["BatchSyncId"])
        assertEquals(3L, entries.single { it["ProductSyncId"] == product }["StockBefore"])
        assertEquals("Producto nuevo antes que lo que lo nombra", "Product", outboxTypes().first())
        assertTrue(engine().sync(SyncTrigger.USER) is SyncOutcome.Completed)
        assertEquals(0, store.pendingChanges())
    }

    @Test
    fun unPedidoConUnRenglonMaloNoDejaNadaAMedias() {
        clearOutbox()
        val before = store.rowCounts()
        assertThrows(WriteRefused::class.java) {
            writes.receiveSupplierOrder(
                SupplierReceipt(
                    "LUIN", "1", category,
                    listOf(SupplierReceiptLine(null, "AAA001", "PRIMERO", 1, BigDecimal.ONE), SupplierReceiptLine("ya-no-existe", "AAA002", "SEGUNDO", 1, BigDecimal.ONE)),
                ),
            )
        }
        assertThrows(WriteRefused::class.java) {
            writes.receiveSupplierOrder(SupplierReceipt("LUIN", "1", category, listOf(SupplierReceiptLine(null, "AAA003", "TERCERO", 1, BigDecimal.ONE, batchNumber = "L1"))))
        }
        assertEquals(before, store.rowCounts())
        assertEquals(0, store.outbox().size)
    }

    @Test
    fun elCelularNoEscribeProductosLotesNiMovimientos() {
        store.apply(StoreChanges(meta = mapOf(SyncEngine.DEVICE_ROLE to DeviceRole.AndroidMobileAdmin.name)))
        clearOutbox()
        val before = store.rowCounts()
        fun refused(block: () -> Unit) = assertEquals(WriteRefused.Reason.FORBIDDEN, assertThrows(WriteRefused::class.java) { block() }.reason)
        refused { writes.saveProduct(values(), SyncIds.random(), isNew = true, originalStock = null) }
        refused { writes.deactivateProduct(product) }
        refused { writes.registerConsumption(product, 1, null) }
        refused { writes.registerEntry(product, 1, "x", 1) }
        refused { writes.receiveBatch(SyncIds.random(), product, "L", LocalDate.of(2030, 1, 1), 1, null, 1) }
        refused { writes.receiveSupplierOrder(SupplierReceipt("LUIN", "1", category, listOf(SupplierReceiptLine(product, "X1", "X", 1, BigDecimal.ONE)))) }
        assertEquals(before, store.rowCounts())
        assertEquals(0, store.outbox().size)
    }
}

/** El aviso del catálogo repetido (K9b): se lee con los datos de ahora, se depura y «Entendido» lo descarta para siempre en este equipo. */
class CatalogNoticeTest {
    private val store = InMemorySyncStore()
    private val writer = io.github.marioalr.tpvca.sync.engine.LocalWriter(store, { "device-b" })
    private val category = "10000000-0000-4000-8000-000000000003"
    private val a = "10000000-0000-4000-8000-0000000000a1"
    private val b = "10000000-0000-4000-8000-0000000000b2"

    private fun product(id: String, code: String, name: String) =
        writer.create("Products", id, mapOf("Code" to code, "Name" to name, "IsActive" to true, "CategorySyncId" to category))

    private fun notices() = io.github.marioalr.tpvca.sync.engine.LocalSide(store).notices(LocalDate.of(2026, 11, 13))

    @Test
    fun seMuestraSeDepuraYSeDescarta() {
        writer.create("Categories", category, mapOf("Name" to "Medicamentos"))
        product(a, "MED-010", "Paracetamol")
        product(b, "med-010 ", "Paracetamol 500")
        val notice = io.github.marioalr.tpvca.sync.engine.SyncNotice(io.github.marioalr.tpvca.sync.engine.SyncNotices.catalogKind(io.github.marioalr.tpvca.domain.business.CatalogDuplicateRules.Kind.Product), a, b)
        store.apply(StoreChanges(meta = mapOf(SyncEngine.NOTICES to io.github.marioalr.tpvca.sync.engine.SyncNotices.write(setOf(notice)))))
        val shown = notices()
        assertEquals(1, shown.catalog.size)
        assertEquals(
            "Posible registro repetido en el catálogo con lo que llegó de otro equipo: producto «Paracetamol» (MED-010) y «Paracetamol 500» (med-010), mismo código. " +
                "Si son lo mismo, desactive uno (sus ventas y lotes se conservan) o cámbiele el código. TPVC no los une solo.",
            shown.catalogText,
        )
        assertTrue("No es un expediente duplicado", shown.duplicates.isEmpty())
        // «Entendido» de los expedientes no lo toca
        io.github.marioalr.tpvca.sync.engine.LocalSide(store).dismiss(io.github.marioalr.tpvca.sync.engine.NoticeKind.DuplicatePatients)
        assertEquals(1, notices().catalog.size)
        // Se le cambió el código: ya no se parecen y se quita
        writer.edit("Products", b, mapOf("Code" to "MED-011"))
        assertTrue(notices().catalog.isEmpty())
        assertTrue(io.github.marioalr.tpvca.sync.engine.SyncNotices.read(store.meta(SyncEngine.NOTICES)).isEmpty())
        // Otra vez repetido y descartado: el mismo par no vuelve aunque vuelva a llegar
        writer.edit("Products", b, mapOf("Code" to "MED-010"))
        store.apply(StoreChanges(meta = mapOf(SyncEngine.NOTICES to io.github.marioalr.tpvca.sync.engine.SyncNotices.write(setOf(notice)))))
        io.github.marioalr.tpvca.sync.engine.LocalSide(store).dismiss(io.github.marioalr.tpvca.sync.engine.NoticeKind.DuplicateCatalog)
        assertTrue(notices().catalog.isEmpty())
        val again = io.github.marioalr.tpvca.sync.engine.SyncNotices.merged(store, emptySet(), setOf(notice))
        assertTrue(again.isEmpty())
    }
}
