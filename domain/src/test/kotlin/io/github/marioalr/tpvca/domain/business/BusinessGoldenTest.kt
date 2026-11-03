package io.github.marioalr.tpvca.domain.business

import io.github.marioalr.tpvca.domain.Clinic
import io.github.marioalr.tpvca.domain.golden.GoldenFile
import io.github.marioalr.tpvca.domain.golden.bool
import io.github.marioalr.tpvca.domain.golden.boolOrNull
import io.github.marioalr.tpvca.domain.golden.dec
import io.github.marioalr.tpvca.domain.golden.decOrNull
import io.github.marioalr.tpvca.domain.golden.int
import io.github.marioalr.tpvca.domain.golden.intOrNull
import io.github.marioalr.tpvca.domain.golden.items
import io.github.marioalr.tpvca.domain.golden.json
import io.github.marioalr.tpvca.domain.golden.jsonObject
import io.github.marioalr.tpvca.domain.golden.jsonStrings
import io.github.marioalr.tpvca.domain.golden.localText
import io.github.marioalr.tpvca.domain.golden.obj
import io.github.marioalr.tpvca.domain.golden.parseDay
import io.github.marioalr.tpvca.domain.golden.parseLocal
import io.github.marioalr.tpvca.domain.golden.parseUtc
import io.github.marioalr.tpvca.domain.golden.productsIn
import io.github.marioalr.tpvca.domain.golden.s
import io.github.marioalr.tpvca.domain.golden.str
import io.github.marioalr.tpvca.domain.golden.toProduct
import io.github.marioalr.tpvca.domain.model.BusinessLineType
import io.github.marioalr.tpvca.domain.model.BusinessPeriod
import io.github.marioalr.tpvca.domain.model.BusinessPeriodKind
import io.github.marioalr.tpvca.domain.model.BusinessRanking
import io.github.marioalr.tpvca.domain.model.BusinessSaleLine
import io.github.marioalr.tpvca.domain.model.BusinessStockMovement
import io.github.marioalr.tpvca.domain.model.BusinessSummary
import io.github.marioalr.tpvca.domain.model.ClinicalService
import io.github.marioalr.tpvca.domain.model.MovementType
import io.github.marioalr.tpvca.domain.model.Product
import io.github.marioalr.tpvca.domain.model.ProductBatch
import io.github.marioalr.tpvca.domain.model.Sale
import io.github.marioalr.tpvca.domain.model.SaleItem
import io.github.marioalr.tpvca.domain.text.NetDecimal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import java.math.BigDecimal
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * El negocio y el inventario: cash-cut, alerts, suggested-purchase, inventory-value y los del panel «Negocio» (business-summary,
 * sales-by-type, best-sellers, purchases y supply-consumption), cada caso desde su `input`.
 */
class BusinessGoldenTest {
    private fun money(value: BigDecimal) = JsonPrimitive(NetDecimal.text(value))

    /** Un importe de los vectores del negocio: siempre con 2 decimales ("1544.00"). */
    private fun m2(value: BigDecimal) = JsonPrimitive(NetDecimal.fixed(value, 2))

    /** Un porcentaje de los vectores del negocio: 1 decimal ("100.0"); null sin dato. */
    private fun pct(value: BigDecimal?): JsonElement = value?.let { JsonPrimitive(NetDecimal.custom(it, 1, 1)) } ?: JsonNull

    private fun zone(name: String): ZoneId = if (name == "UTC") ZoneOffset.UTC else Clinic.zoneOrFallback(name)

    @Test
    fun cashCut() = GoldenFile.load("cash-cut").run { case ->
        val sales = case.input.items("sales").map { s ->
            Sale(
                total = s.dec("total"),
                paymentMethod = s.str("paymentMethod"),
                isCancelled = s.bool("cancelled"),
                items = s.items("items").map { SaleItem(isService = it.bool("isService"), quantity = it.int("quantity"), subtotal = it.dec("subtotal")) },
            )
        }
        val cut = CashCutRules.compute(sales, parseDay(case.input.str("day")))
        jsonObject(
            "totalTickets" to json(cut.totalTicketsCount),
            "activeTickets" to json(cut.activeTicketsCount),
            "cancelledTickets" to json(cut.cancelledTicketsCount),
            "totalSales" to money(cut.totalSalesAmount),
            "totalCancelled" to money(cut.totalCancelledAmount),
            "cash" to money(cut.totalCash),
            "debitCard" to money(cut.totalDebitCard),
            "creditCard" to money(cut.totalCreditCard),
            "transfer" to money(cut.totalTransfer),
            "other" to money(cut.totalOtherPaymentMethods),
            "servicesAmount" to money(cut.totalServicesAmount),
            "servicesCount" to json(cut.totalServicesCount),
            "productsAmount" to money(cut.totalProductsAmount),
            "productUnits" to json(cut.totalProductsUnitsSold),
        )
    }

    private fun alertProduct(n: JsonElement) = Product(
        id = n.int("id"),
        code = n.str("code"),
        name = n.str("name"),
        isActive = n.bool("active", true),
        minStock = n.intOrNull("minStock") ?: 5,
        currentStock = n.int("currentStock"),
        unitsPerPackage = n.int("unitsPerPackage", 1),
        openedUnitsRemaining = n.int("openedUnitsRemaining"),
    )

    @Test
    fun alerts() = GoldenFile.load("alerts")
        .run(setOf("lowStock", "expiration", "inventoryReview", "shortage", "warning", "closeLot"), key = "kind") { case ->
            val input = case.input
            when (input.s("kind")) {
                "lowStock" -> jsonStrings(StockAlertRules.lowStock(input.items("products").map(::alertProduct)).map { it.code })
                "expiration" -> {
                    val today = Clinic.today(java.time.Instant.parse(input.str("now")), zone(input.str("timeZone")))
                    val batches = input.items("batches").map {
                        ProductBatch(
                            batchNumber = it.str("batch"),
                            expirationDate = parseDay(it.str("expiration")),
                            quantity = it.int("quantity"),
                            isDepleted = it.bool("depleted"),
                            closedAtUtc = parseUtc(it.s("closedAtUtc")),
                        ) to Product(isActive = it.bool("productActive", true))
                    }
                    val productOf = batches.associate { (b, p) -> b.batchNumber to p }
                    val (expired, near) = StockAlertRules.splitByExpiration(batches.map { it.first }, today) { productOf[it.batchNumber] }
                    jsonObject(
                        "today" to json(localText(today)),
                        "expired" to jsonStrings(expired.map { it.batchNumber }),
                        "nearExpiry" to jsonStrings(near.map { it.batchNumber }),
                        "status" to JsonObject(batches.associate { (b, _) -> b.batchNumber to JsonPrimitive(b.statusTextOn(today)) }),
                    )
                }
                "inventoryReview" -> {
                    val products = input.items("products").map(::alertProduct)
                    val batches = input.items("batches").map {
                        ProductBatch(productId = it.int("productId"), batchNumber = it.str("batch"), quantity = it.int("quantity"), closedAtUtc = parseUtc(it.s("closedAtUtc")))
                    }
                    jsonObject(
                        "review" to JsonArray(
                            StockReviewRules.inventoryReview(products, batches).map {
                                jsonObject("code" to json(it.code), "alertLine" to json(StockReviewRules.alertLine(it, batches)))
                            },
                        ),
                        "stockBadge" to JsonObject(
                            products.associate { p -> p.code to JsonPrimitive(StockReviewRules.stockBadgeText(p, batches.filter { it.productId == p.id })) },
                        ),
                    )
                }
                "shortage" -> {
                    val p = input.obj("product")
                    val product = Product(
                        name = p.str("name"), currentStock = p.int("currentStock"), unitsPerPackage = p.int("unitsPerPackage", 1),
                        openedUnitsRemaining = p.int("openedUnitsRemaining"),
                    )
                    val shortage = StockReviewRules.shortage(product, input.int("wholePackages"), input.int("individualUnits"))
                    shortage?.let {
                        jsonObject(
                            "available" to json(it.available), "charged" to json(it.charged), "pieces" to json(it.pieces),
                            "warning" to json(StockReviewRules.warningText(listOf(it))),
                        )
                    } ?: JsonNull
                }
                "warning" -> json(
                    StockReviewRules.warningText(
                        input.items("shortages").map { StockShortage(it.str("name"), it.int("available"), it.int("charged"), it.bool("pieces")) },
                    ),
                )
                else -> {
                    val stock = input.int("productStock")
                    val (lot, rest) = StockReviewRules.closingDeltas(input.int("lotQuantity"), input.int("lotLedger"), stock)
                    jsonObject("lotDelta" to json(lot), "restDelta" to json(rest), "stockAfter" to json(stock + lot + rest))
                }
            }
        }

    @Test
    fun suggestedPurchase() = GoldenFile.load("suggested-purchase").run { case ->
        val products = case.input.items("products").map {
            Product(
                code = it.str("code"), name = it.str("name"), categoryName = it.s("category"), isActive = it.bool("active", true),
                price = it.dec("price"), unitCost = it.dec("unitCost"), costExcludesVat = it.bool("costExcludesVat"),
                minStock = it.int("minStock"), currentStock = it.int("currentStock"),
            )
        }
        JsonArray(
            SuggestedPurchaseRules.build(products).map {
                jsonObject(
                    "code" to json(it.productCode),
                    "category" to json(it.categoryName),
                    "suggestedQuantity" to json(it.suggestedQuantity),
                    "unitCost" to money(it.estimatedUnitCost),
                    "costEstimated" to json(it.isCostEstimated),
                    "totalCost" to money(it.estimatedTotalCost),
                    "unitCostText" to json(it.unitCostText),
                    "reason" to json(it.reason),
                )
            },
        )
    }

    @Test
    fun inventoryValue() = GoldenFile.load("inventory-value").run(setOf("product", "dashboard")) { case ->
        if (case.input.s("check") == "product") {
            val p = toProduct(case.input.obj("product")!!)
            jsonObject("effectiveUnitCost" to money(p.effectiveUnitCost), "inventoryValue" to money(p.inventoryValue), "hasCost" to json(p.hasCost))
        } else {
            val (value, withoutCost) = InventoryValueRules.summarize(productsIn(case.input))
            jsonObject("inventoryValue" to money(value), "stockedWithoutCost" to json(withoutCost), "detail" to json(InventoryValueRules.detail(withoutCost)))
        }
    }

    // ================================================================== panel «Negocio»

    private fun bizProduct(n: JsonElement): Product = toProduct(n).copy(unitsPerPackage = n.int("unitsPerPackage", 1))

    private fun bizProducts(input: JsonElement) = input.items("products").map(::bizProduct)

    private fun bizServices(input: JsonElement) =
        input.items("services").map { ClinicalService(id = it.int("id"), code = it.str("code"), name = it.str("name"), category = it.str("category")) }

    private fun bizLines(input: JsonElement): List<BusinessSaleLine> = input.items("sales").flatMap { sale ->
        sale.items("items").map { i ->
            val isService = i.bool("isService")
            BusinessSaleLine(
                saleId = sale.int("id"),
                saleDate = parseLocal(sale.str("date")),
                saleCancelled = sale.bool("cancelled"),
                productId = if (isService) null else i.intOrNull("productId"),
                clinicalServiceId = if (isService) i.intOrNull("serviceId") else null,
                isService = isService,
                code = i.str("code"),
                description = i.str("description"),
                quantity = i.int("quantity"),
                unitPrice = i.dec("unitPrice"),
                subtotal = i.dec("subtotal"),
                unitCost = i.decOrNull("unitCost"),
                isIndividualUnits = i.boolOrNull("isIndividualUnits"),
            )
        }
    }

    private fun movements(input: JsonElement) = input.items("movements").map {
        BusinessStockMovement(
            it.int("productId"), parseLocal(it.str("date")), MovementType.valueOf(it.str("type")), it.int("quantity"), it.decOrNull("unitCost"), it.s("reason"),
        )
    }

    private fun period(input: JsonElement, key: String = "period"): BusinessPeriod =
        input.obj(key).let { BusinessPeriod(parseLocal(it.str("from")).toLocalDate(), parseLocal(it.str("to")).toLocalDate()) }

    private fun type(input: JsonElement): BusinessLineType? = input.s("type")?.let(BusinessLineType::valueOf)

    private fun linesFor(input: JsonElement, periodKey: String = "period", type: BusinessLineType? = null) =
        BusinessReportRules.lines(bizLines(input), bizProducts(input), bizServices(input), period(input, periodKey), type)

    private fun summaryJson(s: BusinessSummary) = jsonObject(
        "sales" to m2(s.sales),
        "cost" to m2(s.cost),
        "profit" to m2(s.profit),
        "marginPercent" to pct(s.marginPercent),
        "tickets" to json(s.tickets),
        "averageTicket" to pct(s.averageTicket),
        "estimatedCostLines" to json(s.estimatedCostLines),
        "missingCostLines" to json(s.missingCostLines),
        "costNote" to json(BusinessReportRules.estimatedCostNote(s.estimatedCostLines, s.missingCostLines)),
    )

    @Test
    fun businessSummary() = GoldenFile.load("business-summary").run(setOf("period", "cost-to-record", "line", "summary", "timeline")) { case ->
        val input = case.input
        when (input.s("check")) {
            "period" -> {
                val utc = parseLocal(input.str("nowUtc")).toInstant(ZoneOffset.UTC)
                val today = Clinic.today(utc, zone(input.str("zone")))
                val kind = BusinessPeriodKind.valueOf(input.str("kind"))
                val from = input.s("customFrom")?.let { parseLocal(it).toLocalDate() }
                val to = input.s("customTo")?.let { parseLocal(it).toLocalDate() }
                val period = BusinessReportRules.periodFor(kind, today, from, to)
                val previous = BusinessReportRules.previousPeriod(kind, period)
                jsonObject(
                    "today" to json(localText(today)), "from" to json(localText(period.from)), "to" to json(localText(period.to)),
                    "previousFrom" to json(localText(previous.from)), "previousTo" to json(localText(previous.to)),
                    "name" to json(BusinessReportRules.periodName(kind)), "text" to json(BusinessReportRules.periodText(period)),
                )
            }
            "cost-to-record" -> BusinessReportRules.costToRecord(bizProduct(input.obj("product")!!), input.bool("individualUnits"))?.let(::m2) ?: JsonNull
            "line" -> {
                val line = linesFor(input).single()
                jsonObject(
                    "type" to json(line.type.name), "name" to json(line.name), "isLoosePieces" to json(line.isLoosePieces),
                    "sales" to m2(line.sales), "cost" to m2(line.cost), "costSource" to json(line.costSource.name), "profit" to m2(line.profit),
                )
            }
            "summary" -> {
                val type = type(input)
                val current = BusinessReportRules.summarize(linesFor(input, "period", type))
                val previous = BusinessReportRules.summarize(linesFor(input, "previous", type))
                val comparison = BusinessReportRules.compare(current, previous)
                jsonObject(
                    "current" to summaryJson(current), "previous" to summaryJson(previous),
                    "salesChangePercent" to pct(comparison.salesChangePercent), "profitChangePercent" to pct(comparison.profitChangePercent),
                    "ticketsChangePercent" to pct(comparison.ticketsChangePercent),
                    "salesChangeText" to json(BusinessReportRules.changeText(comparison.salesChangePercent)),
                )
            }
            else -> JsonArray(
                BusinessReportRules.timeline(linesFor(input, "period", type(input)), period(input)).map {
                    jsonObject("start" to json(localText(it.start)), "label" to json(it.label), "sales" to m2(it.sales), "profit" to m2(it.profit))
                },
            )
        }
    }

    @Test
    fun salesByType() = GoldenFile.load("sales-by-type").run { case ->
        JsonArray(
            BusinessReportRules.byType(linesFor(case.input)).map {
                jsonObject(
                    "type" to json(it.type.name), "name" to json(it.name), "sales" to m2(it.sales), "cost" to m2(it.cost), "profit" to m2(it.profit),
                    "marginPercent" to pct(it.marginPercent), "sharePercent" to pct(it.sharePercent), "lines" to json(it.lines),
                    "estimatedCostLines" to json(it.estimatedCostLines),
                )
            },
        )
    }

    @Test
    fun bestSellers() = GoldenFile.load("best-sellers").run(setOf("products", "services")) { case ->
        val input = case.input
        if (input.s("check") == "services") {
            JsonArray(
                BusinessReportRules.topServices(linesFor(input, "period", type(input)), input.int("top")).map {
                    jsonObject(
                        "serviceId" to json(it.clinicalServiceId), "name" to json(it.name), "type" to json(it.type.name), "count" to json(it.count),
                        "income" to m2(it.income),
                    )
                },
            )
        } else {
            JsonArray(
                BusinessReportRules.topProducts(linesFor(input), type(input), BusinessRanking.valueOf(input.str("ranking")), input.int("top")).map {
                    jsonObject(
                        "productId" to json(it.productId), "name" to json(it.name), "type" to json(it.type.name), "isLoosePieces" to json(it.isLoosePieces),
                        "quantity" to json(it.quantity), "quantityText" to json(it.quantityText), "sales" to m2(it.sales), "cost" to m2(it.cost),
                        "profit" to m2(it.profit), "hasEstimatedCost" to json(it.hasEstimatedCost),
                    )
                },
            )
        }
    }

    @Test
    fun purchases() = GoldenFile.load("purchases").run(setOf("supplier", "purchases")) { case ->
        val input = case.input
        if (input.s("check") == "supplier") {
            jsonObject("isOrder" to json(BusinessStockRules.isSupplierOrder(input.s("reason"))), "supplier" to json(BusinessStockRules.supplierOf(input.s("reason"))))
        } else {
            val p = BusinessStockRules.purchases(movements(input), bizProducts(input), period(input), type(input))
            jsonObject(
                "entries" to json(p.entries), "orders" to json(p.orders), "units" to json(p.units), "cost" to m2(p.cost),
                "estimatedCostLines" to json(p.estimatedCostLines), "missingCostLines" to json(p.missingCostLines),
                "bySupplier" to JsonArray(
                    p.bySupplier.map {
                        jsonObject(
                            "supplier" to json(it.supplier), "entries" to json(it.entries), "units" to json(it.units), "cost" to m2(it.cost),
                            "estimatedCostLines" to json(it.estimatedCostLines),
                        )
                    },
                ),
                "byType" to JsonArray(
                    p.byType.map {
                        jsonObject("type" to json(it.type.name), "name" to json(it.name), "entries" to json(it.entries), "units" to json(it.units), "cost" to m2(it.cost))
                    },
                ),
            )
        }
    }

    @Test
    fun supplyConsumption() = GoldenFile.load("supply-consumption").run { case ->
        val input = case.input
        val c = BusinessStockRules.supplyConsumption(movements(input), linesFor(input), bizProducts(input), period(input), type(input))
        jsonObject(
            "cost" to m2(c.cost), "estimatedCostLines" to json(c.estimatedCostLines), "missingCostLines" to json(c.missingCostLines),
            "rows" to JsonArray(
                c.rows.map {
                    jsonObject(
                        "productId" to json(it.productId), "name" to json(it.name), "type" to json(it.type.name), "isLoosePieces" to json(it.isLoosePieces),
                        "usedInConsultation" to json(it.usedInConsultation), "chargedWithServices" to json(it.chargedWithServices),
                        "quantity" to json(it.quantity), "quantityText" to json(it.quantityText), "cost" to m2(it.cost),
                        "hasEstimatedCost" to json(it.hasEstimatedCost),
                    )
                },
            ),
        )
    }
}
