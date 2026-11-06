package io.github.marioalr.tpvca.sync

import io.github.marioalr.tpvca.domain.consultation.ConsultationAccountRules
import io.github.marioalr.tpvca.domain.model.ClinicalService
import io.github.marioalr.tpvca.domain.model.Product
import io.github.marioalr.tpvca.domain.model.ServiceDefaultProduct
import io.github.marioalr.tpvca.sync.rules.consultation.AccountCatalog
import io.github.marioalr.tpvca.sync.rules.consultation.AccountLine
import io.github.marioalr.tpvca.sync.rules.consultation.AccountLines
import io.github.marioalr.tpvca.sync.rules.consultation.ConsultationAccount
import io.github.marioalr.tpvca.sync.rules.consultation.LineRef
import io.github.marioalr.tpvca.sync.rules.consultation.PrescriptionRow
import io.github.marioalr.tpvca.sync.rules.consultation.ServiceRow
import io.github.marioalr.tpvca.sync.rules.consultation.ServiceSupply
import io.github.marioalr.tpvca.sync.rules.consultation.SheetRows
import io.github.marioalr.tpvca.sync.rules.consultation.SupplyRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDateTime

/** Lo de la cuenta que `sheet-charges.json` no recorre: piezas sueltas, cargos libres de otro nombre, un producto desactivado, renglones sin lista y serializar la cuenta. */
class ConsultationAccountTest {
    private val gasa = Product(code = "CUR-001", name = "Gasa", currentStock = 2, unitsPerPackage = 10, openedUnitsRemaining = 3, price = BigDecimal("50.00"), syncId = "a0000000-0000-4000-8000-000000000001")
    private val alcohol = Product(code = "ANT-001", name = "Alcohol", currentStock = 1, price = BigDecimal("65.50"), syncId = "a0000000-0000-4000-8000-000000000002")
    private val curacion = ClinicalService(
        code = "SERV-002",
        name = "Curación",
        category = "Curaciones",
        price = BigDecimal("250.00"),
        syncId = "b0000000-0000-4000-8000-000000000001",
        defaultProducts = listOf(ServiceDefaultProduct(gasa, 2), ServiceDefaultProduct(alcohol, 1)),
    )

    private fun account(readOnly: Boolean = false) =
        ConsultationAccount(AccountCatalog(listOf(gasa, alcohol), listOf(curacion)), readOnly = readOnly, now = { LocalDateTime.of(2026, 10, 15, 10, 30) })

    @Test
    fun losInsumosDeUnPaqueteVanEnPiezasSueltasEnSuPropioRenglon() {
        val account = account()
        val row = account.chargeService(ServiceRow(serviceName = "Curación", serviceSyncId = curacion.syncId)).row
        val pieces = account.lines.first { it.isIndividualUnits }
        assertEquals("Piezas ind.", pieces.unit)
        assertEquals(BigDecimal("5.00"), pieces.unitPrice)
        assertEquals(2, pieces.quantity)
        assertEquals(BigDecimal("5.00"), row.supplies!![0].unitPrice)

        // El paquete completo del mismo producto va aparte
        account.chargePrescription(PrescriptionRow(medicineName = "Gasa"), gasa)
        assertEquals(listOf("servicio", "producto", "producto", "producto"), account.lines.map { it.lineKind })
        assertEquals(2, account.lines.count { it.catalogSyncId == gasa.syncId })

        // Quitar un paquete o unas piezas no se confunde
        account.unchargeServiceSupply(row.supplies[0], 1)
        assertEquals(1, account.lines.first { it.isIndividualUnits }.quantity)
        assertEquals(1, account.lines.first { it.catalogSyncId == gasa.syncId && !it.isIndividualUnits }.quantity)
    }

    @Test
    fun lasPiezasQueCabenCuentanLosPaquetesYElAbierto() {
        val account = account()
        // 2 paquetes de 10 y 3 sueltas
        assertEquals(23, account.piecesThatFit(gasa, 99))
        account.chargePrescription(PrescriptionRow(medicineName = "Gasa"), gasa)
        assertEquals(13, account.piecesThatFit(gasa, 99))
        assertEquals(1, account.piecesThatFit(alcohol, 5))
        assertEquals(1, account.chargedProductUnits(gasa))
    }

    @Test
    fun unChargedLineIdNegativoSeCobraEnElCargoLibreQueYaEsAunqueSeLlameDistinto() {
        val account = account()
        val id = ConsultationAccountRules.freeChargeId("Venda de yeso 15 cm")
        val line = LineRef.free(id, "Venda de yeso 15 cm (Rollo)")
        val entry = ServiceSupply("Venda de yeso 15 cm (Rollo)", 1, BigDecimal("40.00"), chargedLineId = id, chargedLineKind = "producto", chargedLineSyncId = "a0000000-0000-4000-8000-0000000000ff")
        assertEquals(line, account.serviceSupplyLine(entry))

        // El renglón de la cuenta recibida lleva el otro nombre: se suma a él
        val received = AccountLine(AccountLines.FREE, name = "Venda de yeso 15 cm", unitPrice = BigDecimal("40.00"), quantity = 1)
        val withLine = ConsultationAccount(AccountCatalog(), listOf(received), now = { LocalDateTime.MIN })
        val charged = withLine.chargeServiceSupply(entry, null, line, 2)
        assertTrue(charged.charged)
        assertEquals(3, withLine.lines.single().quantity)
        assertEquals(3, charged.entry.quantity)
        // La clase y el SyncId no cambian: en los demás equipos sigue siendo ese producto
        assertEquals("producto", charged.entry.chargedLineKind)

        val after = withLine.unchargeServiceSupply(charged.entry, 3)
        assertEquals(0, after.quantity)
        assertTrue(withLine.lines.isEmpty())
    }

    @Test
    fun unProductoQueSeDesactivoSeSigueCobrandoEnSuRenglon() {
        val retired = alcohol.copy(isActive = false)
        val service = curacion.copy(defaultProducts = listOf(ServiceDefaultProduct(retired, 1)))
        val withService = ConsultationAccount(AccountCatalog(listOf(gasa), listOf(service), listOf(gasa, retired)), now = { LocalDateTime.MIN })
        val row = withService.chargeService(ServiceRow(serviceName = "Curación", serviceSyncId = service.syncId, quantity = 1)).row
        val line = withService.lines.single { it.lineKind == "producto" }
        assertEquals(retired.syncId, line.catalogSyncId)
        assertEquals("ANT-001", line.code)
        assertEquals(1, row.supplies!!.single().quantity)
        // Lo que no está en ninguna lista, por nombre no se encuentra: cargo libre
        assertTrue(withService.supplyLine(SupplyRow(supplyName = "Otro")).isFree)
    }

    @Test
    fun unRenglonSinListaSeDescuentaComoAntes() {
        val service = curacion.copy(defaultProducts = listOf(ServiceDefaultProduct(alcohol, 1)))
        val catalog = ConsultationAccount(AccountCatalog(listOf(alcohol), listOf(service)), now = { LocalDateTime.MIN })
        catalog.addService(service)
        assertEquals(2, catalog.lines.size)
        val old = ServiceRow(serviceName = "Curación", serviceSyncId = service.syncId, chargedUnits = 1)
        assertEquals(1, catalog.legacyServiceSupplies(old).single().quantity)
        // Una unidad más sin lista también carga sus insumos, y la lista se queda sin nacer
        val more = catalog.changeServiceUnits(old, 1)
        assertNull(more.row.supplies)
        assertEquals(2, catalog.lines.first { it.lineKind == "producto" }.quantity)
        assertEquals(old, catalog.changeServiceUnits(old, -2).row)
        assertTrue(catalog.lines.isEmpty())
    }

    @Test
    fun losMensajesYLoQueNoSeCarga() {
        val account = account()
        val supply = account.chargeSupply(SupplyRow(supplyName = "Cubrebocas"))
        assertFalse(supply.charged)
        assertEquals("'Cubrebocas' no está en el inventario ni tiene precio: se registró en el expediente, pero no se cargó a la cuenta.", supply.message)
        val service = account.chargeService(ServiceRow(serviceName = "Otra cosa"))
        assertFalse(service.charged)
        assertEquals("'Otra cosa' no tiene precio: se registró en el expediente, pero no se cargó a la cuenta.", service.message)
        assertNull(service.row.supplies)

        val locked = account(readOnly = true)
        assertFalse(locked.addService(curacion))
        assertFalse(locked.chargePrescription(PrescriptionRow(medicineName = "Gasa"), gasa).charged)
        assertTrue(locked.lines.isEmpty())
    }

    @Test
    fun elAvisoDeVariosProductos() {
        val account = account()
        account.chargePrescription(PrescriptionRow(medicineName = "Alcohol", recommendedQuantity = 2), alcohol)
        assertEquals("Existencias insuficientes: Alcohol (quedan 1, se cobran 2). Revise el inventario.", account.stockWarning(alcohol))
        assertEquals(account.stockWarning(alcohol), account.stockWarning(listOf(alcohol.syncId, alcohol.syncId, gasa.syncId)))
        account.removeLine(account.lines.single())
        assertNull(account.stockWarning(alcohol))
    }

    @Test
    fun laCuentaSeSerializaConSuEscala() {
        val line = AccountLine(AccountLines.PRODUCT, gasa.syncId, true, "CUR-001", "Gasa", null, "Paquete", "Piezas ind.", BigDecimal("120.50"), 3, "2026-10-15T10:30:00")
        val free = AccountLine(AccountLines.FREE, name = "Férula", unitPrice = BigDecimal("35"), quantity = 1, addedAt = "2026-10-15T10:31:00")
        val text = AccountLines.encode(listOf(line, free))
        assertTrue(text, "\"UnitPrice\":\"120.50\"" in text)
        assertTrue(text, "\"LineKind\":\"libre\"" in text)
        assertFalse(text, "\"CatalogSyncId\":null" in text)
        assertEquals(listOf(line, free), AccountLines.decode(text))
        assertEquals(BigDecimal("361.50"), line.subtotal)
        // La hora con segundos, aunque sean :00
        val account = account()
        account.addService(curacion)
        assertEquals("2026-10-15T10:30:00", account.lines[0].addedAt)
    }

    @Test
    fun losRenglonesConservanLoQueNoConocenYLosIdDeUnaPcAnterior() {
        val json = """[{"rowid":"6f9619ff-8b86-d011-b42d-00c04fc964ff","supplyname":"Gasa","Quantity":2,"UnitPrice":5.50,"ChargedLineId":4,"Futuro":{"a":1}}]"""
        val row = SheetRows.supplies(json, "3f2504e0-4f89-11d3-9a0c-0305e82c3301").single()
        assertEquals(4, row.chargedLineId)
        assertEquals("Gasa", row.supplyName)
        val written = SheetRows.write(listOf(row.copy(quantity = 3)))
        assertTrue(written, "\"ChargedLineId\":4" in written)
        assertTrue(written, "\"Futuro\":{\"a\":1}" in written)
        assertTrue(written, "\"Quantity\":3" in written)

        // Uno nuevo o repetido recibe un RowId; un renglón nuevo de esta app no lleva Id locales
        val fresh = listOf(row, row, SupplyRow(supplyName = "Nuevo").withLine(LineRef.free("Nuevo")))
        var next = 0
        val ids = SheetRows.assignNewRowIds(fresh, { r, id -> r.copy(rowId = id) }, { "id-${next++}" }).map { it.rowId }
        assertEquals(listOf(row.rowId, "id-0", "id-1"), ids)
        val newRow = SheetRows.write(listOf(fresh[2]))
        assertFalse(newRow, "ChargedLineId" in newRow)
    }
}
