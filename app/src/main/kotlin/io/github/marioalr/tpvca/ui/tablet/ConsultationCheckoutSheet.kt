package io.github.marioalr.tpvca.ui.tablet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.marioalr.tpvca.domain.consultation.CartItem
import io.github.marioalr.tpvca.domain.consultation.CheckoutCart
import io.github.marioalr.tpvca.domain.text.MexicanText
import io.github.marioalr.tpvca.sync.rules.consultation.ConsultationDraft
import io.github.marioalr.tpvca.ui.theme.LocalTpvcColors

/**
 * «Terminar consulta» (`ConsultationCheckoutView`): la nota, la receta y el mismo cobro del punto de venta, con la cuenta de la consulta. «Volver a la consulta» permite agregar algo que faltó: la consulta sigue viva hasta cobrar.
 */
@Composable
fun ConsultationCheckoutSheet(workspace: TabletWorkspace, session: ConsultationCheckout, draft: ConsultationDraft) {
    val colors = LocalTpvcColors.current
    val elapsed = rememberElapsed(workspace, session.startedAt)
    SheetFrame {
        Column(Modifier.widthIn(max = 1040.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors["ThemeCard"]).testTag(TabletTags.CHECKOUT)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    THeading("Terminar consulta", size = TType.heading)
                    TCaption("${session.observed.patientName} · duración $elapsed")
                }
                TButton("Volver a la consulta", workspace.consultation::closeCheckout, icon = TIcons.ChevronLeft, enabled = !session.writing, tag = TabletTags.CHECKOUT_BACK)
            }
            TDivider()
            BoxWithConstraints(Modifier.fillMaxWidth().heightIn(max = 620.dp)) {
                if (isWide(760.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { NoteAndLines(workspace, session, draft) }
                        Column(Modifier.width(340.dp).fillMaxHeight().background(colors.surfaceSubtle).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Payment(workspace, session) }
                    }
                } else {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        NoteAndLines(workspace, session, draft)
                        Payment(workspace, session)
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteAndLines(workspace: TabletWorkspace, session: ConsultationCheckout, draft: ConsultationDraft) {
    val colors = LocalTpvcColors.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // A diferencia de la PC, que aquí ya guardó la nota, se escribe al cobrar: decir «guardada» sería falso
        TCard(Modifier.weight(1f), padding = 12.dp) {
            TText("Nota médica: se guarda al cobrar", size = TType.detail, weight = FontWeight.SemiBold)
            TCaption(draft.diagnosis.ifBlank { "Sin diagnóstico capturado" }, maxLines = 2)
        }
        TCard(Modifier.weight(1f), padding = 12.dp) {
            val count = draft.prescriptions.size
            TText("Receta · $count medicamento(s)", size = TType.detail, weight = FontWeight.SemiBold)
            if (count == 0) {
                TCaption("Sin medicamentos recetados.")
            } else {
                // TODO(K10): imprimir en papel; la vista previa trae «Compartir», que también guarda el PDF
                PrescriptionPreviewButton(workspace, Modifier.padding(top = 6.dp))
            }
        }
    }
    THeading("Cuenta de la consulta")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val items = session.observed.items
        items.forEachIndexed { index, item ->
            key(item.productId, item.catalogSyncId, item.code, item.isIndividualUnits) { CartLine(session, item, index) }
        }
        if (items.isEmpty()) TCaption("Sin cargos.", Modifier.padding(8.dp))
    }
    TCaption("Al cobrar se descuentan del inventario los medicamentos e insumos entregados. Si quita todos los cargos, la consulta termina sin registrar una venta.", color = colors.textSecondary)
}

@Composable
private fun CartLine(session: ConsultationCheckout, item: CartItem, index: Int) {
    // Tocado «Cobrar», la cuenta ya no cambia desde la pantalla
    val open = !session.charging
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.weight(1f)) {
            TText(item.name, size = TType.detail, weight = FontWeight.SemiBold)
            TCaption("${item.code} · ${item.stockText} · ${MexicanText.currency(item.unitPrice)}")
        }
        TIconLabel("−", "Una menos: ${item.name}", TabletTags.cartLess(item.name), open) { session.change { decreaseQuantity(index) } }
        QuantityField(item.quantity, open) { value -> session.change { setQuantity(index, value) } }
        TIconLabel("+", "Una más: ${item.name}", null, open) { session.change { increaseQuantity(index) } }
        TText(MexicanText.currency(item.subtotal), Modifier.widthIn(min = 76.dp), size = TType.detail, weight = FontWeight.SemiBold, mono = true)
        TIconButton(TIcons.Close, "Quitar: ${item.name}", { session.change { removeFromCart(index) } }, tag = TabletTags.cartRemove(item.name), enabled = open)
    }
}

@Composable
private fun TIconLabel(text: String, description: String, tag: String?, enabled: Boolean, onClick: () -> Unit) {
    TButton(text, onClick, Modifier.width(TOUCH_MIN), enabled = enabled, tag = tag, description = description)
}

/** La cantidad escrita a mano: mínimo 1, y mientras está vacía no cambia la cuenta. */
@Composable
private fun QuantityField(quantity: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    var text by remember(quantity) { mutableStateOf(quantity.toString()) }
    TField(
        text,
        { typed ->
            text = typed.filter(Char::isDigit).take(4)
            text.toIntOrNull()?.let(onChange)
        },
        Modifier.width(64.dp),
        readOnly = !enabled,
        keyboardType = KeyboardType.Number,
    )
}

/** El cobro (`PaymentPanelView`): total, forma de pago, recibido y cambio. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Payment(workspace: TabletWorkspace, session: ConsultationCheckout) {
    val colors = LocalTpvcColors.current
    val cart = session.observed
    val open = !session.charging
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            TText("Total", weight = FontWeight.SemiBold)
            TCaption("${cart.totalItems} artículo(s)")
        }
        TText(MexicanText.currency(cart.totalAmount), size = TType.title, weight = FontWeight.Bold, mono = true)
    }
    FieldLabel("Forma de pago")
    TChips(CheckoutCart.PAYMENT_METHODS, cart.selectedPaymentMethod, { method -> session.change { selectedPaymentMethod = method } }, enabled = open)
    if (cart.isCash) {
        TField(cart.cashReceivedText, { text -> session.change { typeCashReceived(text) } }, Modifier.fillMaxWidth(), label = "Recibido", placeholder = "Exacto", readOnly = !open, keyboardType = KeyboardType.Decimal, tag = TabletTags.CHECKOUT_RECEIVED)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TButton("Exacto", { session.change { setExactCash() } }, enabled = open)
            for (amount in cart.quickCashAmounts) {
                key(amount.toPlainString()) { TButton(MexicanText.currency(amount).removeSuffix(".00"), { session.change { setCashReceived(amount) } }, enabled = open) }
            }
        }
        if (cart.hasChange) AmountRow("Cambio", MexicanText.currency(cart.changeDue), colors.successSubtle, colors.successText)
        if (cart.isCashShort) AmountRow("Faltan", MexicanText.currency(cart.missingAmount), colors.dangerSubtle, colors.dangerText)
    }
    // Revisión 20: un inventario mal contado nunca impide cobrar; solo se avisa
    cart.stockWarning?.let { TNotice(it, colors.warningSubtle, colors.warningText) }
    TButton(cart.chargeText, workspace.consultation::charge, Modifier.fillMaxWidth(), kind = TButtonKind.Primary, enabled = cart.canCharge && !session.charging, tag = TabletTags.CHECKOUT_CHARGE)
}

@Composable
private fun AmountRow(label: String, value: String, fill: androidx.compose.ui.graphics.Color, content: androidx.compose.ui.graphics.Color) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(fill).padding(horizontal = 10.dp, vertical = 8.dp)) {
        TText(label, Modifier.weight(1f), weight = FontWeight.SemiBold, color = content)
        TText(value, weight = FontWeight.Bold, color = content, mono = true)
    }
}
