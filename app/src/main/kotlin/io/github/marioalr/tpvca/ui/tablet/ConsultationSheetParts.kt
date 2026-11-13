package io.github.marioalr.tpvca.ui.tablet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.marioalr.tpvca.data.clinic.ConsultationCatalog
import io.github.marioalr.tpvca.domain.business.StockReviewRules
import io.github.marioalr.tpvca.domain.model.Product
import io.github.marioalr.tpvca.domain.text.MexicanText
import io.github.marioalr.tpvca.ui.theme.LocalTpvcColors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Las marcas de las hojas de la consulta, para las pruebas y TalkBack. */
object SheetTags {
    const val PRESCRIPTION = "tablet-sheet-receta"
    const val SUPPLIES = "tablet-sheet-insumos"
    const val SERVICES = "tablet-sheet-servicios"
    const val CATALOG = "tablet-sheet-catalogo"
    const val ALLERGY_ALERT = "tablet-alerta-alergia"
    const val ALLERGY_CANCEL = "tablet-alerta-no-recetar"
    const val ALLERGY_OVERRIDE = "tablet-alerta-recetar"
    const val MEDICINE = "tablet-receta-medicamento"
    const val MEDICINE_CLEAR = "tablet-receta-quitar-medicamento"
    const val DOSAGE = "tablet-receta-indicacion"
    const val QUANTITY = "tablet-receta-cantidad"
    const val ADD = "tablet-receta-agregar"
    const val SHOW_CHOSEN = "tablet-receta-mostrar"
    const val OPEN_CATALOG = "tablet-sheet-abrir-catalogo"
    const val SAVE = "tablet-sheet-guardar"
    const val CLOSE = "tablet-sheet-cerrar"
    const val REPEAT = "tablet-receta-repetir"
    const val PREVIEW = "tablet-receta-vista-previa"
    const val OPEN_PRESCRIPTION = "tablet-abrir-receta"
    const val OPEN_SUPPLIES = "tablet-abrir-insumos"
    const val OPEN_SERVICES = "tablet-abrir-servicios"
    const val INDICATIONS_TOGGLE = "tablet-receta-frecuentes"
    const val INDICATION_TOGGLE = "tablet-indicacion-frecuentes"
    const val PEDIATRIC_RULE = "tablet-pediatrica-regla"
    const val PEDIATRIC_WEIGHT = "tablet-pediatrica-peso"
    const val PEDIATRIC_USE = "tablet-pediatrica-usar"
    const val INDICATION_FIELD = "tablet-indicacion-nueva"
    const val INDICATION_SAVE = "tablet-indicacion-guardar"
    const val INDICATION_CANCEL = "tablet-indicacion-cancelar"
    const val SUPPLY_SEARCH = "tablet-insumo-buscar"
    const val SUPPLY_INSTRUCTIONS = "tablet-insumo-uso"
    const val SUPPLY_QUANTITY = "tablet-insumo-cantidad"
    const val SUPPLY_ADD = "tablet-insumo-agregar"
    const val SERVICE_SEARCH = "tablet-servicio-buscar"
    const val SERVICE_NOTES = "tablet-servicio-notas"
    const val SERVICE_QUANTITY = "tablet-servicio-cantidad"
    const val SERVICE_ADD = "tablet-servicio-agregar"
    const val SERVICE_SUPPLY_SEARCH = "tablet-servicio-insumo-buscar"
    const val CATALOG_SEARCH = "tablet-catalogo-buscar"
    const val CHECKOUT_PRINT = "tablet-cobro-imprimir"
    const val CHECKOUT_PREVIEW = "tablet-cobro-vista-previa"
    const val CHECKOUT_SAVE = "tablet-cobro-guardar-pdf"
    const val CORRECT = "tablet-corregir-receta"
    const val CORRECT_SAVE = "tablet-corregir-guardar"
    const val CORRECT_CANCEL = "tablet-corregir-cancelar"
    const val CORRECT_NEW = "tablet-corregir-nuevo"
    const val CORRECT_TYPED = "tablet-corregir-como-esta"
    const val SEE_PRESCRIPTION = "tablet-ver-receta"
    fun dispense(rowId: String?) = "tablet-receta-surte-$rowId"
    fun show(rowId: String?) = "tablet-receta-mostrar-$rowId"
    fun change(rowId: String?) = "tablet-receta-cambiar-$rowId"
    fun remove(rowId: String?) = "tablet-receta-quitar-$rowId"
    fun picked(name: String) = "tablet-sugerencia-$name"
    fun indication(title: String) = "tablet-frecuente-$title"
    fun rule(name: String) = "tablet-pediatrica-opcion-$name"
    fun supplyLess(rowId: String?) = "tablet-insumo-menos-$rowId"
    fun supplyMore(rowId: String?) = "tablet-insumo-mas-$rowId"
    fun supplyLine(rowId: String?) = "tablet-insumo-piezas-$rowId"
    fun supplyRemove(rowId: String?) = "tablet-insumo-quitar-$rowId"
    fun serviceLess(rowId: String?) = "tablet-servicio-menos-$rowId"
    fun serviceMore(rowId: String?) = "tablet-servicio-mas-$rowId"
    fun serviceRemove(rowId: String?) = "tablet-servicio-quitar-$rowId"
    fun serviceAdjust(rowId: String?) = "tablet-servicio-ajustar-$rowId"
    fun entryLess(name: String) = "tablet-servicio-insumo-menos-$name"
    fun entryMore(name: String) = "tablet-servicio-insumo-mas-$name"
    fun entryRemove(name: String) = "tablet-servicio-insumo-quitar-$name"
    fun catalogPick(name: String) = "tablet-catalogo-$name"
    fun correctName(index: Int) = "tablet-corregir-nombre-$index"
    fun correctDosage(index: Int) = "tablet-corregir-indicacion-$index"
    fun correctRemove(index: Int) = "tablet-corregir-quitar-$index"
}

/**
 * Buscar mientras se escribe sin trabar la pantalla (3,000 productos): espera a que se deje de escribir, cancela la búsqueda anterior y calcula fuera del hilo principal, sobre el último catálogo leído. Solo es para **mostrar**: al agregar, el paso vuelve a mirar el catálogo de ese momento.
 */
internal class SheetSearch<T>(
    private val consultation: ConsultationController,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val compute: (ConsultationCatalog, String) -> T,
    private val publish: (String, T) -> Unit,
) {
    private var job: Job? = null

    /** [pause]: en 0 al abrir la lista, sin esperar. */
    fun request(query: String, pause: Long = PAUSE_MILLIS) {
        job?.cancel()
        job = scope.launch {
            if (pause > 0) delay(pause)
            val catalog = consultation.catalogForSearch()
            val found = withContext(io) { compute(catalog, query) }
            publish(query, found)
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    companion object {
        const val PAUSE_MILLIS = 120L
    }
}

/** La insignia de existencias (`Product.StockBadgeText`). Las sugerencias no leen los lotes de 3,000 productos: un lote abierto en negativo se ve en Inventario y al cobrar. */
internal fun stockBadge(product: Product): String = StockReviewRules.stockBadgeText(product, emptyList())

/** Una casilla de la PC, con el alto de un dedo. */
@Composable
internal fun TCheck(text: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, tag: String? = null) {
    val colors = LocalTpvcColors.current
    var m = modifier.heightIn(min = TOUCH_MIN).clip(RoundedCornerShape(8.dp)).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
    if (tag != null) m = m.testTag(tag)
    Row(m.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val shape = RoundedCornerShape(4.dp)
        Box(
            Modifier.size(20.dp).clip(shape).background(if (checked) (if (enabled) colors.accentStrong else colors.textSecondary) else colors.surface).border(1.dp, if (checked) colors.accentStrong else colors["ThemeControlBorder"], shape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(TIcons.Check, null, Modifier.size(14.dp), tint = colors["ThemeAccentText"])
        }
        TText(text, size = TType.detail, color = if (enabled) colors.text else colors.textSecondary)
    }
}

/** La tarjeta de una hoja: título y botones arriba, lo de en medio se desplaza y el pie siempre se ve, también con el teclado abierto. */
@Composable
internal fun SheetCard(
    title: String,
    subtitle: String,
    tag: String,
    onClose: () -> Unit,
    maxWidth: Dp = 860.dp,
    actions: @Composable () -> Unit = {},
    footer: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalTpvcColors.current
    SheetFrame {
        Column(Modifier.widthIn(max = maxWidth).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors["ThemeCard"]).testTag(tag)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    THeading(title, size = TType.heading)
                    TCaption(subtitle)
                }
                actions()
                TIconButton(TIcons.Close, "Cerrar", onClose, tag = SheetTags.CLOSE)
            }
            TDivider()
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
            TDivider()
            footer()
        }
    }
}

/** Una lista de sugerencias debajo de un campo, abierta mientras se escribe. */
@Composable
internal fun SuggestionBox(caption: String, onClose: (() -> Unit)?, content: @Composable () -> Unit) {
    val colors = LocalTpvcColors.current
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, colors["ThemeBorder"], shape).background(colors["ThemeCard"]).padding(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TCaption(caption, Modifier.weight(1f).padding(horizontal = 8.dp))
            if (onClose != null) TButton("Cerrar lista", onClose, kind = TButtonKind.Ghost)
        }
        // La lista de la PC mide 200 de alto y se desplaza; aquí se desplaza junto con la hoja
        Column { content() }
    }
}

/** Un producto de una lista de sugerencias: el nombre, lo de abajo, sus existencias y su precio. [marked]: el que toma «Agregar» si no se toca otro. */
@Composable
internal fun ProductSuggestion(product: Product, detail: String, marked: Boolean, onPick: () -> Unit, price: java.math.BigDecimal = product.price, packageText: String? = null) {
    val colors = LocalTpvcColors.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = TOUCH_MIN).clip(RoundedCornerShape(8.dp)).background(if (marked) colors["ThemeSelectionBackground"] else colors["ThemeCard"])
            .clickable(role = Role.Button, onClick = onPick).testTag(SheetTags.picked(product.name)).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            TText(product.name, size = TType.detail, weight = FontWeight.SemiBold, maxLines = 2)
            TCaption(detail, maxLines = 2)
            if (packageText != null) TCaption(packageText)
        }
        TBadge(stockBadge(product), if (product.currentStock <= 0) colors.warningSubtle else colors.surfaceSubtle, if (product.currentStock <= 0) colors.warningText else colors.text)
        TText(MexicanText.currency(price), Modifier.widthIn(min = 72.dp), size = TType.detail, weight = FontWeight.SemiBold, mono = true)
    }
}

/**
 * Las piezas escritas a mano en un renglón: se aplican tras una pausa y al cerrar, como la PC al salir del cuadro. En la tablet no hay Enter a la mano y un «Guardar» no quita el foco. Lo que no es número vuelve a mostrar las piezas reales.
 */
@Composable
internal fun RowQuantityField(quantity: Int, description: String, enabled: Boolean, tag: String?, onCommit: (Int) -> Unit) {
    var text by remember(quantity) { mutableStateOf(quantity.toString()) }
    val shownQuantity by rememberUpdatedState(quantity)
    val commit by rememberUpdatedState(onCommit)
    val typed by rememberUpdatedState(text)
    LaunchedEffect(text) {
        val value = text.toIntOrNull()
        if (value != null && value != quantity) {
            delay(QUANTITY_PAUSE_MILLIS)
            commit(value)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val value = typed.toIntOrNull()
            if (value != null && value != shownQuantity) commit(value)
        }
    }
    TField(
        text,
        { text = it.filter(Char::isDigit).take(4) },
        Modifier.width(64.dp),
        readOnly = !enabled,
        keyboardType = KeyboardType.Number,
        tag = tag,
        description = description,
    )
}

private const val QUANTITY_PAUSE_MILLIS = 800L
