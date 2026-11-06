package io.github.marioalr.tpvca.domain.consultation

import io.github.marioalr.tpvca.domain.golden.GoldenFile
import io.github.marioalr.tpvca.domain.golden.bool
import io.github.marioalr.tpvca.domain.golden.dec
import io.github.marioalr.tpvca.domain.golden.int
import io.github.marioalr.tpvca.domain.golden.intOrNull
import io.github.marioalr.tpvca.domain.golden.items
import io.github.marioalr.tpvca.domain.golden.json
import io.github.marioalr.tpvca.domain.golden.jsonObject
import io.github.marioalr.tpvca.domain.golden.jsonStrings
import io.github.marioalr.tpvca.domain.golden.obj
import io.github.marioalr.tpvca.domain.golden.parseLocal
import io.github.marioalr.tpvca.domain.golden.s
import io.github.marioalr.tpvca.domain.golden.str
import io.github.marioalr.tpvca.domain.golden.toProduct
import io.github.marioalr.tpvca.domain.model.Consultation
import io.github.marioalr.tpvca.domain.model.Vitals
import io.github.marioalr.tpvca.domain.search.CatalogSearchRules
import io.github.marioalr.tpvca.domain.text.NetDecimal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import java.math.BigDecimal

/** La consulta en `:domain`: cie10-catalog, cie10-search, pediatric-dose y checkout, cada caso desde su `input`. */
class ConsultationGoldenTest {
    private fun money(value: BigDecimal?): JsonElement = if (value == null) JsonNull else JsonPrimitive(NetDecimal.text(value))

    /** 38.0 y 38 son el mismo número; `GoldenFile` solo lo sabe en el primer nivel. */
    private fun number(value: Double?): JsonElement = when {
        value == null -> JsonNull
        value == Math.rint(value) -> JsonPrimitive(value.toLong())
        else -> JsonPrimitive(value)
    }

    @Test
    fun cie10Catalog() = GoldenFile.load("cie10-catalog").run { _ ->
        JsonArray(Cie10Catalog.all.map { jsonObject("code" to json(it.code), "name" to json(it.name), "category" to json(it.category)) })
    }

    @Test
    fun cie10Search() = GoldenFile.load("cie10-search").run(setOf("search", "suggestions")) { case ->
        val found = if (case.string("check") == "search") {
            Cie10Catalog.search(case.input.s("query"), case.input.int("max"))
        } else {
            CatalogSearchRules.cie10Suggestions(case.input.s("text"))
        }
        jsonStrings(found.map { it.code })
    }

    @Test
    fun pediatricDose() = GoldenFile.load("pediatric-dose").run(setOf("dose", "suggest-weight")) { case ->
        val input = case.input
        if (case.string("check") == "dose") {
            val rule = input.obj("rule")?.let {
                PediatricDoseRule(it.str("medication"), it.str("doseText"), it.s("mlPerKg")?.toDouble(), it.intOrNull("dropsPerMl"))
            }
            val text = input.s("weight")
            val weight = PediatricDoseRules.parseNumber(text) ?: 0.0
            jsonObject(
                "displayName" to json(rule?.displayName),
                "ml" to number(rule?.calculateMl(weight)),
                "drops" to number(rule?.calculateDrops(weight)),
                "result" to json(PediatricDoseRules.resultText(rule, text)),
                "dosage" to json(PediatricDoseRules.dosageText(rule, text)),
            )
        } else {
            val consultations = input.items("consultations").map {
                Consultation(date = parseLocal(it.str("date")), vitals = Vitals(weight = it.s("weight")?.let(NetDecimal::parse)))
            }
            JsonPrimitive(PediatricDoseRules.suggestWeight(input.bool("hasActiveConsultation"), input.s("currentWeight"), consultations))
        }
    }

    @Test
    fun checkout() = GoldenFile.load("checkout").run(setOf("parse-amount", "suggest-cash", "checkout")) { case ->
        val input = case.input
        when (case.string("check")) {
            "parse-amount" -> money(CheckoutRules.parseAmount(input.s("text")))
            "suggest-cash" -> JsonArray(CheckoutRules.suggestCashAmounts(input.dec("total")).map(::money))
            else -> runCart(input)
        }
    }

    private fun runCart(input: JsonObject): JsonElement {
        val notices = ArrayList<JsonElement>()
        val cart = CheckoutCart(input.s("emptyChargeText"), input.str("chargeSuffix")) { notices += jsonObject("text" to json(it.text), "ok" to json(it.ok)) }
        for (op in input.items("ops")) {
            val kind = op.str("op")
            when {
                kind == "add-product" -> cart.addProduct(toProduct(op.obj("product")!!), op.int("quantity"))
                kind == "add-line" -> cart.addLine(
                    CartItem(productId = op.int("productId"), code = op.str("code"), name = op.str("name"), unitPrice = op.dec("unitPrice"), quantity = op.int("quantity"), isService = op.bool("isService")),
                )
                kind == "increase" -> cart.increaseQuantity(op.int("value"))
                kind == "decrease" -> cart.decreaseQuantity(op.int("value"))
                kind.startsWith("set-quantity-") -> cart.setQuantity(kind.removePrefix("set-quantity-").toInt(), op.int("value"))
                kind == "cash-text" -> cart.typeCashReceived(op.str("value"))
                kind == "set-cash" -> cart.setCashReceived(op.dec("value"))
                kind == "payment" -> cart.selectedPaymentMethod = op.str("value")
                else -> error("Una operación nueva en checkout.json: $kind")
            }
        }
        return jsonObject(
            "cart" to JsonArray(
                cart.items.map {
                    jsonObject("name" to json(it.name), "quantity" to json(it.quantity), "subtotal" to money(it.subtotal), "stockText" to json(it.stockText))
                },
            ),
            "totalItems" to json(cart.totalItems),
            "total" to money(cart.totalAmount),
            "isCash" to json(cart.isCash),
            "cashReceived" to money(cart.cashReceived),
            "cashReceivedText" to json(cart.cashReceivedText),
            "changeDue" to money(cart.changeDue),
            "hasChange" to json(cart.hasChange),
            "missingAmount" to money(cart.missingAmount),
            "canCharge" to json(cart.canCharge),
            "chargeText" to json(cart.chargeText),
            "quickCash" to JsonArray(cart.quickCashAmounts.map(::money)),
            "receipt" to jsonObject("cashReceived" to money(cart.receiptCash.received), "change" to money(cart.receiptCash.change)),
            "missingText" to json(cart.missingText),
            "stockWarning" to json(cart.stockWarning),
            "notices" to JsonArray(notices),
        )
    }
}
