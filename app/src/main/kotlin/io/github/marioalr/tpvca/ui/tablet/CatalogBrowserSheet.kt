package io.github.marioalr.tpvca.ui.tablet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.marioalr.tpvca.domain.model.ClinicalService
import io.github.marioalr.tpvca.domain.model.Product
import io.github.marioalr.tpvca.domain.search.CatalogSearchRules
import io.github.marioalr.tpvca.domain.search.TextSearch
import io.github.marioalr.tpvca.domain.text.MexicanText
import io.github.marioalr.tpvca.ui.theme.LocalTpvcColors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

/**
 * El explorador del catálogo (`CatalogBrowserViewModel`): medicamentos, insumos o servicios para la receta o la consulta. Lo abren las hojas de receta, insumos y servicios; lo elegido se
 * agrega con la hoja que lo abrió. Un medicamento solo se elige (su indicación se escribe en la receta) y los medicamentos van con existencias primero.
 */
class CatalogBrowser internal constructor(private val consultation: ConsultationController, scope: CoroutineScope, io: CoroutineDispatcher) {
    enum class Mode(val title: String, val subtitle: String, val actionText: String, val hint: String) {
        Medications(
            "Catálogo de Medicamentos",
            "Los que hay en existencia van primero. Elija el medicamento y luego escriba su indicación en la receta.",
            "Elegir",
            "Con existencias primero · Elegir lo pasa a la receta para escribir su indicación",
        ),
        Supplies("Catálogo de Insumos", "Material y consumibles del inventario. Agregar lo pone en la consulta.", "Agregar", "Agregar lo pone en la consulta"),
        Services("Catálogo de Servicios", "Procedimientos y servicios del consultorio. Agregar lo pone en la consulta.", "Agregar", "Agregar lo pone en la consulta"),
    }

    var mode by mutableStateOf(Mode.Medications)
        private set
    var query by mutableStateOf("")
        private set
    var products by mutableStateOf<List<Product>>(emptyList())
        private set
    var services by mutableStateOf<List<ClinicalService>>(emptyList())
        private set

    /** La hoja que lo abrió, a la que se vuelve al cerrar. */
    internal var returnTo: ConsultationSheet? = null

    private val finder = SheetSearch(consultation, scope, io, { catalog, q ->
        when (mode) {
            Mode.Medications -> CatalogSearchRules.browserMedications(catalog.account.products, q) to emptyList()
            Mode.Supplies -> CatalogSearchRules.browserSupplies(catalog.account.products, q) to emptyList()
            Mode.Services -> {
                val normalized = TextSearch.normalize(q)
                val list = if (normalized.isEmpty()) catalog.account.services else catalog.account.services.filter { TextSearch.matches(normalized, it.name, it.code, it.category, it.description) }
                emptyList<Product>() to list.sortedWith(compareBy(MexicanText.nameOrder()) { it.name })
            }
        }
    }, ::publish)

    val isServices: Boolean get() = mode == Mode.Services

    val summary: String
        get() = if (isServices) (if (services.size == 1) "1 servicio" else "${services.size} servicios") else (if (products.size == 1) "1 producto" else "${products.size} productos")

    val hasNoResults: Boolean get() = if (isServices) services.isEmpty() else products.isEmpty()

    internal fun open(which: Mode, returnTo: ConsultationSheet?) {
        mode = which
        this.returnTo = returnTo
        query = ""
        products = emptyList()
        services = emptyList()
        finder.request("", pause = 0)
    }

    internal fun close() = finder.cancel()

    fun search(text: String) {
        query = text
        finder.request(text, pause = 0)
    }

    private fun publish(q: String, found: Pair<List<Product>, List<ClinicalService>>) {
        if (q != query) return
        products = found.first
        services = found.second
    }

    /** El botón de un producto: un medicamento se elige en la receta; un insumo se agrega. El catálogo se cierra. */
    fun pick(product: Product) {
        // El segundo de dos toques: el catálogo ya se cerró, y otro `closeSheet` cerraría también la hoja de abajo
        if (consultation.sheet != ConsultationSheet.CatalogBrowser) return
        when (mode) {
            Mode.Medications -> consultation.prescription.choose(product)
            Mode.Supplies -> consultation.suppliesEditor.add(product)
            Mode.Services -> return
        }
        consultation.closeSheet()
    }

    fun pick(service: ClinicalService) {
        if (consultation.sheet != ConsultationSheet.CatalogBrowser) return
        consultation.servicesEditor.add(service)
        consultation.closeSheet()
    }
}

/** La hoja del catálogo: el mismo aspecto que el catálogo del punto de venta. */
@Composable
internal fun CatalogBrowserSheet(workspace: TabletWorkspace) {
    val browser = workspace.consultation.catalogBrowser
    val colors = LocalTpvcColors.current
    DisposableEffect(Unit) { onDispose { browser.close() } }
    SheetCard(
        title = browser.mode.title,
        subtitle = browser.mode.subtitle,
        tag = SheetTags.CATALOG,
        onClose = workspace.consultation::closeSheet,
        maxWidth = 900.dp,
        actions = { TCaption(browser.summary) },
        footer = { TCaption(browser.mode.hint, Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) },
    ) {
        TField(browser.query, browser::search, Modifier.fillMaxWidth(), placeholder = "Nombre, genérico, código o descripción", description = "Buscar en el catálogo", tag = SheetTags.CATALOG_SEARCH)
        if (browser.isServices) {
            for (service in browser.services) {
                key(service.syncId.ifEmpty { service.name }) { ServiceLine(service, browser.mode.actionText) { browser.pick(service) } }
            }
        } else {
            for (product in browser.products) {
                key(product.syncId.ifEmpty { product.name }) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            ProductSuggestion(product, listOf(product.code, product.genericName.orEmpty(), product.presentation.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "), marked = false, onPick = { browser.pick(product) })
                        }
                        TButton(browser.mode.actionText, { browser.pick(product) }, icon = TIcons.Plus, tag = SheetTags.catalogPick(product.name))
                    }
                }
            }
        }
        if (browser.hasNoResults) TText("Nada coincide con lo escrito.", Modifier.fillMaxWidth().padding(vertical = 24.dp), size = TType.detail, color = colors.textSecondary)
    }
}

@Composable
private fun ServiceLine(service: ClinicalService, actionText: String, onPick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = TOUCH_MIN), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            TText(service.name, size = TType.detail, weight = FontWeight.SemiBold)
            TCaption(listOf(service.category, "${service.estimatedDurationMinutes} min", service.description).filter { it.isNotBlank() }.joinToString(" · "), maxLines = 2)
        }
        TText(MexicanText.currency(service.price), Modifier.widthIn(min = 72.dp), size = TType.detail, weight = FontWeight.SemiBold, mono = true)
        TButton(actionText, onPick, icon = TIcons.Plus, tag = SheetTags.catalogPick(service.name))
    }
}
