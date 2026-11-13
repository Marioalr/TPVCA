package io.github.marioalr.tpvca.sync.rules.consultation

import io.github.marioalr.tpvca.domain.business.StockReviewRules
import io.github.marioalr.tpvca.domain.consultation.ConsultationAccountRules
import io.github.marioalr.tpvca.domain.model.ClinicalService
import io.github.marioalr.tpvca.domain.model.Product
import io.github.marioalr.tpvca.domain.search.CatalogMatchingRules
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * El catálogo de este equipo cuando se abrió la cuenta. [products] y [services] son los disponibles (activos); [allProducts] incluye los desactivados, para encontrar por su SyncId un
 * renglón que cargó un producto que se desactivó a media consulta.
 */
data class AccountCatalog(
    val products: List<Product> = emptyList(),
    val services: List<ClinicalService> = emptyList(),
    val allProducts: List<Product> = products,
) {
    /** El producto de un renglón por su nombre (`CatalogStore.FindCatalogProduct`). */
    fun findProduct(name: String?): Product? = CatalogMatchingRules.findProductByName(products, name)

    fun availableProduct(syncId: String?): Product? = syncId?.let { id -> products.firstOrNull { it.syncId.equals(id, ignoreCase = true) } }
}

/** Lo que dejó «Se surte aquí»: si quedó cargado (`DispenseInClinic`) y el aviso de existencias, que no impide nada. */
data class PrescriptionCharge(val charged: Boolean, val warning: String? = null)

/** Lo que dejó cargar un insumo: el renglón con lo que anotó, y [message] para mostrar si no se cargó. */
data class SupplyCharge(val charged: Boolean, val row: SupplyRow, val message: String? = null)

/** Lo que dejó cargar un servicio o cambiar sus unidades: el renglón con su lista, y [message] para mostrar si no se cargó. */
data class ServiceCharge(val charged: Boolean, val row: ServiceRow, val message: String? = null)

/** Lo que dejó cargar piezas de un insumo de la lista de un servicio: el insumo como quedó. */
data class ServiceSupplyCharge(val charged: Boolean, val entry: ServiceSupply)

/**
 * La cuenta de la consulta (`ViewModels/Patients/State/ConsultationAccount.cs`): el honorario y lo que se carga durante la consulta (receta que se surte aquí, insumos y servicios con
 * sus insumos); todo se cobra al terminar. Sin base ni pantalla: trabaja sobre [catalog] y deja la cuenta en [lines], una lista que no cambia y se serializa con
 * [AccountLines.encode]; los renglones de las hojas son valores, y cada acción regresa el renglón como quedó, que quien la usa guarda en su hoja. Como en la PC, poner el folio y
 * `chargedUnits` en el renglón es de quien la usa. Vectores: `contracts/golden/sheet-charges.json`.
 *
 * Revisión 20: un inventario mal contado nunca impide cargar; solo se avisa.
 *
 * @param feeOverrideSyncId el honorario que usaba el equipo que envió la consulta.
 * @param readOnly la consulta se envió a otro equipo y aquí no se le agrega nada (`IsHandedOff` y los candados de la pantalla).
 * @param now la hora del consultorio; ninguna regla lee el reloj.
 */
class ConsultationAccount(
    val catalog: AccountCatalog,
    lines: List<AccountLine> = emptyList(),
    val feeOverrideSyncId: String? = null,
    var readOnly: Boolean = false,
    private val now: () -> LocalDateTime,
) {
    var lines: List<AccountLine> = lines
        private set

    /** El servicio de "consulta" del catálogo que se cobra como honorario al terminar. */
    val feeService: ClinicalService? get() = ConsultationAccountRules.feeService(catalog.services, feeOverrideSyncId)

    val feeName: String get() = ConsultationAccountRules.feeName(feeService)

    val feeAmount: BigDecimal get() = ConsultationAccountRules.feeAmount(feeService)

    /** Honorario + todo lo cargado durante la consulta (lo que se cobrará al terminar). */
    val total: BigDecimal get() = ConsultationAccountRules.total(feeAmount, lines.map { it.subtotal })

    // ------------------------------------------------------------------ la cuenta

    private fun add(
        ref: LineRef,
        code: String,
        name: String,
        genericName: String?,
        presentation: String?,
        unit: String,
        unitPrice: BigDecimal,
        quantity: Int,
        isIndividualUnits: Boolean = false,
    ): Boolean {
        if (readOnly) return false
        val line = AccountLine(
            lineKind = ref.kind,
            catalogSyncId = if (ref.isFree) null else ref.syncId,
            isIndividualUnits = isIndividualUnits,
            code = code,
            name = name,
            genericName = genericName,
            presentation = presentation,
            unit = unit,
            unitPrice = unitPrice,
            quantity = quantity,
            addedAt = now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
        )
        lines = AccountLines.add(lines, ref, line) ?: return false
        return true
    }

    private fun chargeProduct(product: Product, quantity: Int, displayName: String? = null): Boolean =
        add(LineRef.product(product), product.code, displayName ?: product.name, product.genericName, product.presentation, product.unit, product.price, quantity)

    /** [ref]: el renglón libre de la cuenta que ya tiene el insumo (puede llevar otro nombre); sin él, el de su nombre. */
    private fun chargeFreeLine(code: String?, name: String, category: String?, unit: String, price: BigDecimal, quantity: Int, ref: LineRef? = null): Boolean {
        if (quantity <= 0 || price.signum() <= 0) return false
        return add(ref ?: LineRef.free(name), ConsultationAccountRules.freeChargeCode(code), name, category, null, unit, price, quantity)
    }

    private fun uncharge(ref: LineRef, quantity: Int, isIndividualUnits: Boolean? = null) {
        AccountLines.remove(lines, ref, quantity, isIndividualUnits)?.let { lines = it }
    }

    /**
     * Un renglón que llega del punto de venta (`AddCartItemToActiveConsultation`): «Pasar a la cuenta de la consulta» y los insumos de «Agregar a la consulta de…». Con su nombre, su precio y su modo; el mismo
     * producto o servicio en el mismo modo (paquetes o piezas sueltas) suma a su renglón. Un servicio sin `SyncId` (los de muestra de la PC cuando el catálogo está vacío) va en un renglón propio.
     */
    fun addPointOfSaleLine(
        kind: String,
        catalogSyncId: String?,
        code: String,
        name: String,
        genericName: String?,
        presentation: String?,
        unit: String,
        unitPrice: BigDecimal,
        quantity: Int,
        isIndividualUnits: Boolean = false,
    ): Boolean {
        if (quantity <= 0) return false
        val ref = when {
            kind == AccountLines.PRODUCT && catalogSyncId != null -> LineRef.product(catalogSyncId)
            kind == AccountLines.SERVICE -> LineRef(AccountLines.SERVICE, catalogSyncId)
            else -> LineRef.free(name)
        }
        return add(ref, code, name, genericName, presentation, unit, unitPrice, quantity, isIndividualUnits)
    }

    /**
     * «Agregar a la consulta de…» del punto de venta (`PosViewModel.AddServiceToActiveConsultation`, que no es el de la consulta): el honorario y cada insumo por defecto con **sus piezas tal cual**, en piezas sueltas si el
     * paquete trae varias y a su precio por pieza. Un insumo con 0 piezas no se carga (la PC lo rechaza en `AddCartItemToActiveConsultation`). `false` si no se pudo cargar el honorario.
     */
    fun addPointOfSaleService(service: ClinicalService): Boolean {
        val fee = addPointOfSaleLine(
            AccountLines.SERVICE, service.syncId.ifEmpty { null }, service.code, service.name, "Servicio Clínico • ${service.category}",
            "Honorario • ${service.estimatedDurationMinutes} min", SERVICE_UNIT, service.price, 1,
        )
        if (!fee) return false
        for (defaultProduct in service.defaultProducts) {
            val product = defaultProduct.product ?: continue
            val individual = product.hasMultipleUnitsPerPackage
            addPointOfSaleLine(
                AccountLines.PRODUCT, product.syncId.ifEmpty { null }, product.code, product.name, product.genericName, product.presentation,
                if (individual) "Piezas ind." else product.unit, product.individualUnitPrice, defaultProduct.quantity, isIndividualUnits = individual,
            )
        }
        return true
    }

    /** Quita un renglón de la cuenta (la X en «Cuenta de la consulta»). */
    fun removeLine(line: AccountLine) {
        if (readOnly) return
        lines = lines - line
    }

    // ------------------------------------------------------------------ receta

    /** "Se surte aquí" marcado a mano: un medicamento del inventario se carga aunque no alcancen sus existencias; `charged` false si no está en el inventario (se receta, no se cobra). */
    fun chargePrescription(item: PrescriptionRow, knownProduct: Product? = null): PrescriptionCharge {
        val product = prescriptionProduct(item, knownProduct)
        val quantity = ConsultationAccountRules.prescriptionChargeQuantity(item.recommendedQuantity, product) ?: return PrescriptionCharge(false)
        if (!chargeProduct(product!!, quantity, item.medicineName)) return PrescriptionCharge(false)
        return PrescriptionCharge(true, stockWarning(product))
    }

    /** "Se surte aquí" al agregar un medicamento a la receta: marcado y cargado solo si su producto tiene existencias; con 0 o en negativo queda desmarcado y sin cargo. */
    fun dispenseByDefault(item: PrescriptionRow, knownProduct: Product? = null): PrescriptionCharge =
        if (ConsultationAccountRules.dispenseByDefault(prescriptionProduct(item, knownProduct))) chargePrescription(item, knownProduct) else PrescriptionCharge(false)

    /** El producto de un renglón de la receta: el conocido si sigue siendo ese renglón, si no el de su nombre. */
    private fun prescriptionProduct(item: PrescriptionRow, knownProduct: Product?): Product? =
        if (knownProduct != null && CatalogMatchingRules.productMatchesName(knownProduct, item.medicineName)) knownProduct else catalog.findProduct(item.medicineName)

    fun unchargePrescription(item: PrescriptionRow) {
        val product = catalog.findProduct(item.medicineName) ?: return
        uncharge(LineRef.product(product), maxOf(1, item.recommendedQuantity))
    }

    /** El aviso de existencias de un producto con lo que ya está en la cuenta (paquetes y piezas sueltas de todos sus renglones); `null` si alcanza. No impide nada. */
    fun stockWarning(product: Product): String? {
        val own = lines.filter(LineRef.product(product)::matches)
        if (own.isEmpty()) return null
        val shortage = shortageOf(product, own) ?: return null
        return StockReviewRules.warningText(listOf(shortage))
    }

    /** El aviso de varios productos de la cuenta (los del inventario con esos SyncId, en ese orden y sin repetir), juntos en un solo texto; `null` si todos alcanzan. */
    fun stockWarning(productSyncIds: Iterable<String>): String? {
        val shortages = productSyncIds.distinct().mapNotNull { id ->
            val product = catalog.availableProduct(id) ?: return@mapNotNull null
            shortageOf(product, lines.filter(LineRef.product(product)::matches))
        }
        return StockReviewRules.warningText(shortages)
    }

    private fun shortageOf(product: Product, own: List<AccountLine>) =
        StockReviewRules.shortage(product, own.filterNot { it.isIndividualUnits }.sumOf { it.quantity }, own.filter { it.isIndividualUnits }.sumOf { it.quantity })

    // ------------------------------------------------------------------ insumos de la hoja

    /** Carga el insumo recién agregado (todas sus piezas) y anota con qué renglón de la cuenta se cargó; sin producto y sin precio no se carga y [SupplyCharge.message] lo dice. */
    fun chargeSupply(item: SupplyRow, knownProduct: Product? = null): SupplyCharge {
        val product = knownProduct ?: catalog.findProduct(item.supplyName)
        if (!chargeSupplyUnits(item, product, maxOf(1, item.quantity))) {
            return SupplyCharge(false, item, "'${item.supplyName}' no está en el inventario ni tiene precio: se registró en el expediente, pero no se cargó a la cuenta.")
        }
        return SupplyCharge(true, item.withLine(if (product != null) LineRef.product(product) else LineRef.free(item.supplyName)))
    }

    /** Carga piezas de un insumo: como el producto dado o, sin él, como cargo libre a su precio ([freeLine] si el renglón ya tiene uno, aunque se llame distinto). false si el cargo libre no tiene precio. */
    fun chargeSupplyUnits(item: SupplyRow, product: Product?, units: Int, freeLine: LineRef? = null): Boolean =
        if (product != null) {
            chargeProduct(product, units, item.supplyName)
        } else {
            chargeFreeLine(SUPPLY_CODE, item.supplyName, SUPPLY_CATEGORY, PIECES, item.unitPrice, units, freeLine)
        }

    /** El renglón de la cuenta de un insumo de la hoja ([SheetRows.resolveSupplyLine]). */
    fun supplyLine(item: SupplyRow): LineRef = SheetRows.resolveSupplyLine(item, catalog.products, catalog.allProducts)

    /** Descuenta piezas de un insumo de la cuenta, del mismo renglón con que se cargaron. */
    fun unchargeSupplyUnits(item: SupplyRow, units: Int) = uncharge(supplyLine(item), units)

    /** Piezas de un producto que ya están en la cuenta (el primer renglón de ese producto, como en la PC). */
    fun chargedProductUnits(product: Product): Int = lines.firstOrNull(LineRef.product(product)::matches)?.quantity ?: 0

    /** Piezas que todavía caben en sus existencias: solo dice si hay que avisar, ya no topa lo que se carga. */
    fun piecesThatFit(product: Product, requested: Int): Int {
        if (product.hasMultipleUnitsPerPackage) {
            val own = lines.filter(LineRef.product(product)::matches)
            val wholePackages = own.filterNot { it.isIndividualUnits }.sumOf { it.quantity }
            val individualUnits = own.filter { it.isIndividualUnits }.sumOf { it.quantity }
            val available = maxOf(0, product.currentStock - wholePackages) * product.effectiveUnitsPerPackage + maxOf(0, product.openedUnitsRemaining)
            return ServiceRowSupplies.piecesThatFit(requested, available, individualUnits)
        }
        return ServiceRowSupplies.piecesThatFit(requested, product.currentStock, chargedProductUnits(product))
    }

    // ------------------------------------------------------------------ servicios

    private fun findService(item: ServiceRow): ClinicalService? = SheetRows.resolveService(item, catalog.services)

    private fun serviceRef(item: ServiceRow, service: ClinicalService?): LineRef = if (service != null) LineRef.service(service) else LineRef.free(item.serviceName)

    /**
     * Carga el servicio recién agregado (todas sus unidades con sus insumos por defecto, o su cargo libre) y le pone al renglón su lista: los insumos por defecto por las unidades, o
     * vacía en un cargo libre. La configuración del servicio no cambia. Un cargo libre sin precio no se carga y [ServiceCharge.message] lo dice.
     */
    fun chargeService(item: ServiceRow): ServiceCharge {
        val units = maxOf(1, item.quantity)
        val service = findService(item)
        if (service != null) {
            if (!chargeServiceLine(service, units)) return ServiceCharge(false, item)
            return ServiceCharge(true, item.copy(supplies = chargeAddedPieces(ServiceRowSupplies.addUnits(emptyList(), service.defaultProducts, units))))
        }
        if (!chargeFreeRow(item, units)) {
            return ServiceCharge(false, item, "'${item.serviceName}' no tiene precio: se registró en el expediente, pero no se cargó a la cuenta.")
        }
        return ServiceCharge(true, item.copy(supplies = emptyList()))
    }

    private fun chargeFreeRow(item: ServiceRow, units: Int): Boolean =
        chargeFreeLine(item.serviceCode, item.serviceName, ConsultationAccountRules.serviceLineGeneric(item.category), SERVICE_UNIT, item.price, units)

    /** El renglón del servicio en la cuenta, por sus unidades (sin sus insumos). */
    private fun chargeServiceLine(service: ClinicalService, units: Int): Boolean = add(
        LineRef.service(service),
        service.code,
        service.name,
        ConsultationAccountRules.serviceLineGeneric(service.category),
        ConsultationAccountRules.serviceLinePresentation(service.estimatedDurationMinutes),
        SERVICE_UNIT,
        service.price,
        units,
    )

    /** Carga una unidad del servicio con sus insumos por defecto, sin renglón en la hoja (`AddServiceToActiveConsultation`); false si no se pudo. */
    fun addService(service: ClinicalService?): Boolean {
        if (service == null || !chargeServiceLine(service, 1)) return false
        for (defaultProduct in service.defaultProducts) {
            val product = defaultProduct.product ?: continue
            chargeServiceProductUnits(product, ServiceRowSupplies.piecesPerUnit(defaultProduct))
        }
        return true
    }

    /** Piezas de un insumo de un servicio cargadas como el producto, con su nombre; las de un paquete de varias piezas, a su precio por pieza y en su propio renglón. */
    private fun chargeServiceProductUnits(product: Product, units: Int): Boolean {
        val isIndividual = product.hasMultipleUnitsPerPackage
        return add(
            LineRef.product(product),
            product.code,
            product.name,
            product.genericName,
            product.presentation,
            if (isIndividual) "Piezas ind." else product.unit,
            product.individualUnitPrice,
            units,
            isIndividual,
        )
    }

    /** Sin producto, como el cargo libre que ya tiene el insumo ([freeLine]) o el de su nombre, a su precio; false si no tiene precio. */
    private fun chargeServiceSupplyUnits(entry: ServiceSupply, product: Product?, units: Int, freeLine: LineRef? = null): Boolean {
        if (units <= 0) return false
        if (product != null) return chargeServiceProductUnits(product, units)
        if (entry.unitPrice.signum() <= 0) return false
        return add(freeLine ?: LineRef.free(entry.supplyName), SUPPLY_CODE, entry.supplyName, SUPPLY_CATEGORY, null, PIECES, entry.unitPrice, units)
    }

    /** Carga lo que [ServiceRowSupplies.addUnits] sumó a la lista, cada insumo en el renglón de la cuenta con que se cobra. Lo que no se pudo cargar sale de la lista. */
    private fun chargeAddedPieces(change: ServiceRowSupplies.Change): List<ServiceSupply> {
        val out = change.list.toMutableList()
        for (pieces in change.pieces) {
            val entry = out[pieces.index]
            out[pieces.index] = chargeServiceSupplyOnItsLine(entry, pieces.product, pieces.count) ?: entry.copy(quantity = entry.quantity - pieces.count)
        }
        return out.filter { it.quantity > 0 }
    }

    /**
     * Carga piezas de un insumo de la lista en su renglón de la cuenta, el mismo que encuentran + / − y la X: un cargo libre a su precio; un producto como ese producto; y si este equipo
     * ya no lo tiene activo, en ese mismo renglón con el nombre y el precio del insumo. Regresa el insumo como quedó, o `null` si no se pudo.
     */
    private fun chargeServiceSupplyOnItsLine(entry: ServiceSupply, product: Product?, units: Int): ServiceSupply? {
        if (units <= 0) return null
        val line = serviceSupplyLine(entry)
        val charged = if (line.isFree) {
            chargeServiceSupplyUnits(entry, null, units, line)
        } else {
            val lineProduct = product?.takeIf { line.syncId.equals(it.syncId, ignoreCase = true) } ?: catalog.availableProduct(line.syncId)
            if (lineProduct != null) {
                chargeServiceProductUnits(lineProduct, units)
            } else {
                add(line, SUPPLY_CODE, entry.supplyName, null, null, PIECES, entry.unitPrice, units)
            }
        }
        return if (charged) entry.withRefreshedLine(line) else null
    }

    /** El renglón de la cuenta de un insumo de un servicio: las mismas reglas que un insumo de la hoja. */
    fun serviceSupplyLine(entry: ServiceSupply): LineRef = SheetRows.resolveSupplyLine(entry, catalog.products, catalog.allProducts)

    /** Carga piezas más de un insumo de la lista (+ o agregar): el producto con que se cargó o su cargo libre. Regresa el insumo con sus piezas. */
    fun chargeServiceSupply(entry: ServiceSupply, product: Product?, line: LineRef, units: Int): ServiceSupplyCharge {
        if (!chargeServiceSupplyUnits(entry, product, units, line.takeIf { it.isFree })) return ServiceSupplyCharge(false, entry)
        return ServiceSupplyCharge(true, entry.copy(quantity = entry.quantity + units).withRefreshedLine(line))
    }

    /** Descuenta piezas de un insumo de la lista, del mismo renglón con que se cargaron. Regresa el insumo como quedó (quien la usa lo saca de la lista en 0 piezas). */
    fun unchargeServiceSupply(entry: ServiceSupply, units: Int): ServiceSupply {
        val count = minOf(units, entry.quantity)
        if (count <= 0) return entry
        val line = serviceSupplyLine(entry)
        unchargeSupplyLine(line, count)
        return entry.copy(quantity = entry.quantity - count).withRefreshedLine(line)
    }

    private fun unchargeSupplyLine(line: LineRef, count: Int) {
        uncharge(line, count, if (line.isFree) null else catalog.availableProduct(line.syncId)?.hasMultipleUnitsPerPackage)
    }

    /** La lista de un renglón de esta consulta que no la tiene: lo que descontaría hoy quitarlo. No carga nada ni toca el renglón. */
    fun legacyServiceSupplies(item: ServiceRow): List<ServiceSupply> = ServiceRowSupplies.legacySupplies(findService(item), maxOf(0, item.chargedUnits))

    /**
     * Cambia las unidades cargadas de un renglón de servicio (+ o −). Con lista: el servicio y los insumos por defecto de esas unidades; lo ajustado a mano se queda. Sin lista, como antes:
     * el servicio con sus insumos por defecto de hoy por cada unidad. `charged` false si no se pudo cargar. `quantity` y `chargedUnits` del renglón son de quien la usa, como en la PC.
     */
    fun changeServiceUnits(item: ServiceRow, delta: Int): ServiceCharge {
        if (delta == 0) return ServiceCharge(true, item)
        val service = findService(item)
        if (delta > 0) {
            if (service == null) return ServiceCharge(chargeFreeRow(item, delta), item)
            if (!chargeServiceLine(service, delta)) return ServiceCharge(false, item)
            val supplies = item.supplies
            if (supplies != null) {
                return ServiceCharge(true, item.copy(supplies = chargeAddedPieces(ServiceRowSupplies.addUnits(supplies, service.defaultProducts, delta))))
            }
            for (defaultProduct in service.defaultProducts) {
                val product = defaultProduct.product ?: continue
                chargeServiceProductUnits(product, ServiceRowSupplies.piecesPerUnit(defaultProduct) * delta)
            }
            return ServiceCharge(true, item)
        }

        val units = -delta
        val supplies = item.supplies
        if (supplies == null) {
            unchargeServiceUnits(item, units)
            return ServiceCharge(true, item)
        }
        uncharge(serviceRef(item, service), units)
        val change = ServiceRowSupplies.removeUnits(supplies, service?.defaultProducts, units)
        for (pieces in change.pieces) unchargeSupplyLine(serviceSupplyLine(pieces.entry), pieces.count)
        return ServiceCharge(true, item.copy(supplies = change.list))
    }

    /** Quitar el renglón: descuenta lo que cargó en esta consulta. Con lista, el servicio por sus unidades y cada insumo con sus piezas; sin lista, como antes ([unchargeServiceUnits]). */
    fun unchargeServiceRow(item: ServiceRow, units: Int) {
        if (item.supplies == null) {
            unchargeServiceUnits(item, units)
            return
        }
        uncharge(serviceRef(item, findService(item)), units)
        for (pieces in ServiceRowSupplies.unchargeAll(item.supplies)) unchargeSupplyLine(serviceSupplyLine(pieces.entry), pieces.count)
    }

    /** Descuenta unidades de un servicio sin lista: su renglón y los insumos por defecto que el servicio tiene hoy, o su cargo libre. */
    fun unchargeServiceUnits(item: ServiceRow, units: Int) {
        val service = findService(item)
        uncharge(serviceRef(item, service), units)
        for (defaultProduct in service?.defaultProducts.orEmpty()) {
            val product = defaultProduct.product ?: continue
            uncharge(LineRef.product(product), ServiceRowSupplies.piecesPerUnit(defaultProduct) * units, product.hasMultipleUnitsPerPackage)
        }
    }

    private companion object {
        const val SUPPLY_CODE = "INSUMO"
        const val SUPPLY_CATEGORY = "Material clínico"
        const val PIECES = "Piezas"
        const val SERVICE_UNIT = "Servicio"
    }
}
