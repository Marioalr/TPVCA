package io.github.marioalr.tpvca.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.marioalr.tpvca.data.clinic.InventoryEntry
import io.github.marioalr.tpvca.data.clinic.MovementRow
import io.github.marioalr.tpvca.domain.business.InventoryListRules
import io.github.marioalr.tpvca.domain.business.InventoryRow
import io.github.marioalr.tpvca.domain.business.MovementRules
import io.github.marioalr.tpvca.domain.business.StockTone
import io.github.marioalr.tpvca.domain.business.SuggestedPurchaseRules
import io.github.marioalr.tpvca.domain.business.StockAlertRules
import io.github.marioalr.tpvca.domain.model.ProductBatch
import io.github.marioalr.tpvca.domain.text.MexicanText
import io.github.marioalr.tpvca.ui.link.PatientDataWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private enum class StockFilter(val label: String) { All("Todo"), Meds("Medicamentos"), Supplies("Insumos"), Notices("Con aviso") }

private fun pill(tone: StockTone): Pair<Color, Color> = when (tone) {
    StockTone.Ok -> PhoneColors.Success to PhoneColors.SuccessSoft
    StockTone.Low -> PhoneColors.AmberDark to PhoneColors.AmberSoft
    StockTone.Review -> PhoneColors.Danger to PhoneColors.DangerSoft
}

/** "Paracetamol · Caja con 20 · MED-014", con "lote vence 12 nov" o "abierto 3/5 pzas" en lugar del código si hay algo que decir. */
fun inventoryDetail(row: InventoryRow, today: LocalDate): String {
    val p = row.product
    val last = row.nearestBatch?.let { "lote ${if (it.expirationDate.isBefore(today)) "venció" else "vence"} ${PhoneFormat.shortDate(it.expirationDate, today)}" } ?: InventoryListRules.openedText(p) ?: p.code.takeIf { it.isNotBlank() }
    return listOfNotNull(p.genericName?.takeIf { it.isNotBlank() }, p.presentation?.takeIf { it.isNotBlank() }, last).joinToString(" · ")
}

/** Inventario, solo para consultar: las insignias y los avisos son de las reglas de K1. Busca como la PC ([InventoryListRules.search]). */
@Composable
fun InventarioScreen(env: PhoneEnv, dataVersion: Long, startWithNotices: Boolean, nav: PhoneNav) {
    val today = env.today()
    var query by remember { mutableStateOf("") }
    var filterName by rememberSaveable(startWithNotices) { mutableStateOf(if (startWithNotices) StockFilter.Notices.name else StockFilter.All.name) }
    val filter = StockFilter.valueOf(filterName)
    val entries by load(today, refresh = dataVersion) { env.reader.inventory(today) }
    val shown by produceState<Loaded<List<InventoryEntry>>>(Loaded.Loading, entries, query, filter) {
        val loaded = entries
        if (loaded !is Loaded.Ready) {
            value = when (loaded) {
                Loaded.Failed -> Loaded.Failed
                else -> Loaded.Loading
            }
            return@produceState
        }
        if (query.isNotEmpty()) delay(120)
        value = Loaded.Ready(
            withContext(Dispatchers.Default) {
                val filtered = loaded.value.filter {
                    when (filter) {
                        StockFilter.All -> true
                        StockFilter.Meds -> it.row.isMedication
                        StockFilter.Supplies -> !it.row.isMedication
                        StockFilter.Notices -> it.row.hasAlert
                    }
                }
                val byId = filtered.associateBy { it.row.product.id }
                InventoryListRules.search(filtered.map { it.row }, query, MexicanText.nameOrder()).mapNotNull { byId[it.product.id] }
            },
        )
    }

    Column(Modifier.fillMaxSize().testTag(PhoneTags.INVENTARIO)) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TitleRow("Inventario") {
                Row(
                    Modifier
                        .heightIn(min = 48.dp)
                        .disabledUntilNextVersion(PhoneText.STOCK_ENTRY),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(10.dp)).background(PhoneColors.Hairline).border(1.dp, PhoneColors.Border, RoundedCornerShape(10.dp)).padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(PhoneIcons.Box, null, Modifier.size(16.dp), tint = PhoneColors.Muted)
                        PText("Entrada", size = 13.sp, weight = FontWeight.Medium, color = PhoneColors.Muted)
                    }
                }
            }
            SearchField(query, { query = it }, "Nombre, genérico o código", "Buscar producto", PhoneTags.PRODUCT_SEARCH)
            ChipRow {
                for (option in StockFilter.entries) Chip(option.label, option == filter, ChipLooks.List) { filterName = option.name }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            Loads(shown) { rows ->
                if (rows.isEmpty()) {
                    PCard {
                        Message(
                            when {
                                query.isNotBlank() -> "Ningún producto coincide con la búsqueda."
                                filter == StockFilter.Notices -> "Sin avisos en el inventario."
                                else -> "Todavía no hay productos en este equipo."
                            },
                        )
                    }
                } else {
                    LazyColumn(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(PhoneColors.Surface).border(1.dp, PhoneColors.Border, RoundedCornerShape(16.dp))) {
                        itemsIndexed(rows, key = { _, e -> e.syncId }) { index, entry ->
                            if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(PhoneColors.Border))
                            InventoryListRow(entry, today) { nav.open(PhoneDetail.Producto(entry.syncId)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InventoryListRow(entry: InventoryEntry, today: LocalDate, onClick: () -> Unit) {
    val row = entry.row
    val (fg, bg) = pill(row.tone)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 68.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            PText(row.product.name, size = 15.sp, weight = FontWeight.Medium)
            PText(inventoryDetail(row, today), size = 12.sp, color = PhoneColors.Muted)
        }
        Column(Modifier.weight(0.6f, fill = false), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Pill(row.badge, fg, bg)
            PText(PhoneFormat.money(row.product.price), size = 12.sp, color = PhoneColors.Muted, tabular = true)
        }
    }
}

/** El producto: existencias, precio y costo, lo sugerido por la regla de la PC, sus lotes abiertos y sus últimos movimientos (límite de 20). */
@Composable
fun ProductoScreen(env: PhoneEnv, syncId: String, dataVersion: Long, nav: PhoneNav) {
    // El motivo de un movimiento puede traer el nombre de un paciente
    PatientDataWindow()
    val today = env.today()
    val state by load(syncId, today, refresh = dataVersion) {
        env.reader.inventory(today).firstOrNull { it.syncId == syncId } to env.reader.movements(syncId)
    }
    Column(Modifier.fillMaxSize().testTag(PhoneTags.PRODUCTO)) {
        DetailHeader("Inventario", nav)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Loads(state) { (entry, movements) ->
                if (entry == null) Message(PhoneText.COPY_NOT_FOUND) else ProductBody(entry.row, movements, today)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(PhoneColors.DisabledFill).disabledUntilNextVersion("Registrar entrada de mercancía"),
                    contentAlignment = Alignment.Center,
                ) {
                    PText("Registrar entrada de mercancía", Modifier.padding(8.dp), size = 15.sp, weight = FontWeight.SemiBold, color = PhoneColors.Soft, align = TextAlign.Center)
                }
                PText("Disponible en la siguiente versión", Modifier.fillMaxWidth(), size = 12.sp, color = PhoneColors.Muted, align = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun ProductBody(row: InventoryRow, movements: List<MovementRow>, today: LocalDate) {
    val p = row.product
    val stockColor = when (row.tone) {
        StockTone.Ok -> PhoneColors.Text
        StockTone.Low -> PhoneColors.AmberDark
        StockTone.Review -> PhoneColors.Danger
    }
    val low = StockAlertRules.isLowStock(p)
    Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Heading(p.name, size = 21.sp, weight = FontWeight.Bold)
        PText(listOfNotNull(p.genericName, p.presentation, p.code).filter { it.isNotBlank() }.joinToString(" · "), size = 13.sp, color = PhoneColors.Muted)
        val (fg, bg) = pill(row.tone)
        Pill(row.badge, fg, bg, Modifier.padding(top = 4.dp))
    }
    val stats: @Composable (Modifier) -> Unit = { modifier ->
        StatCard("Existencias", io.github.marioalr.tpvca.domain.business.StockReviewRules.number(p.currentStock), "Mínimo ${p.minStock}", stockColor, if (row.tone == StockTone.Ok) PhoneColors.Muted else stockColor, modifier)
        StatCard("Precio", PhoneFormat.money(p.price), if (p.hasCost) "Costo ${PhoneFormat.money(p.effectiveUnitCost)}" else "Sin costo", PhoneColors.Text, PhoneColors.Muted, modifier)
        StatCard(
            "Sugerido",
            if (low) SuggestedPurchaseRules.suggestedQuantity(p.currentStock, p.minStock).toString() else "—",
            if (low) "compra sugerida" else "sin pedir",
            PhoneColors.Text,
            PhoneColors.Muted,
            modifier,
        )
    }
    if (isBigText()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { stats(Modifier.fillMaxWidth()) }
    } else {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) { stats(Modifier.weight(1f)) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Heading("Lotes abiertos", Modifier.padding(horizontal = 4.dp))
        PCard {
            val lots = row.batches.filter { !it.isClosed }.sortedBy { it.expirationDate }
            if (lots.isEmpty()) Message("Sin lotes abiertos")
            lots.forEachIndexed { index, batch ->
                if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(PhoneColors.Border))
                BatchRow(batch, today)
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Heading("Últimos movimientos", Modifier.padding(horizontal = 4.dp))
        PCard {
            if (movements.isEmpty()) Message("Sin movimientos")
            movements.forEachIndexed { index, m ->
                if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(PhoneColors.Border))
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        PText(listOfNotNull(MovementRules.shortName(m.type), m.reason?.takeIf { it.isNotBlank() }).joinToString(" · "))
                        PText(PhoneFormat.dayAndTime(m.date, today), size = 12.sp, color = PhoneColors.Muted)
                    }
                    PText(MovementRules.deltaText(m.delta), weight = FontWeight.SemiBold, color = if (m.delta > 0) PhoneColors.Success else PhoneColors.Danger, tabular = true)
                }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, note: String, valueColor: Color, noteColor: Color, modifier: Modifier, valueSize: TextUnit = 22.sp) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(PhoneColors.Surface).border(1.dp, PhoneColors.Border, RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PText(label, size = 11.sp, weight = FontWeight.Medium, color = PhoneColors.Muted)
        FigureText(value, valueSize, color = valueColor)
        PText(note, size = 11.sp, weight = FontWeight.Medium, color = noteColor)
    }
}

@Composable
private fun BatchRow(batch: ProductBatch, today: LocalDate) {
    val days = ChronoUnit.DAYS.between(today, batch.expirationDate)
    val near = days <= StockAlertRules.NEAR_EXPIRY_DAYS
    val text = when {
        days < 0 -> "Venció el ${PhoneFormat.shortDate(batch.expirationDate, today)}"
        near -> "Vence el ${PhoneFormat.shortDate(batch.expirationDate, today)} · en $days ${if (days == 1L) "día" else "días"}"
        else -> "Vence el ${PhoneFormat.shortDate(batch.expirationDate, today)}"
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            PText("Lote ${batch.batchNumber}", weight = FontWeight.Medium)
            PText(text, size = 12.sp, weight = if (near) FontWeight.Medium else FontWeight.Normal, color = if (days < 0) PhoneColors.Danger else if (near) PhoneColors.Amber else PhoneColors.Muted)
        }
        PText(io.github.marioalr.tpvca.domain.business.StockReviewRules.number(batch.quantity), size = 15.sp, weight = FontWeight.SemiBold, color = if (batch.quantity < 0) PhoneColors.Danger else PhoneColors.Text, tabular = true)
    }
}
