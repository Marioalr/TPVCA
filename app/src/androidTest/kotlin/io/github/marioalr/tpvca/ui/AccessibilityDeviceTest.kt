package io.github.marioalr.tpvca.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.accessibility.disableAccessibilityChecks
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.then
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult.AccessibilityCheckResultType
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityViewCheckResult
import com.google.android.apps.common.testing.accessibility.framework.checks.TouchTargetSizeCheck
import com.google.android.apps.common.testing.accessibility.framework.integrations.espresso.AccessibilityValidator
import org.hamcrest.Description
import org.hamcrest.TypeSafeMatcher
import io.github.marioalr.tpvca.data.RoomSyncStore
import io.github.marioalr.tpvca.data.SqlCipher
import io.github.marioalr.tpvca.data.TpvcaDatabase
import io.github.marioalr.tpvca.data.clinic.ClinicReader
import io.github.marioalr.tpvca.dev.NoCloud
import io.github.marioalr.tpvca.dev.PhoneDemoSeed
import io.github.marioalr.tpvca.dev.phoneDemoExtras
import io.github.marioalr.tpvca.dev.tabletDemoExtras
import io.github.marioalr.tpvca.documents.SharedDocuments
import io.github.marioalr.tpvca.domain.Clinic
import io.github.marioalr.tpvca.sync.engine.LocalSide
import io.github.marioalr.tpvca.sync.engine.StoreChanges
import io.github.marioalr.tpvca.sync.engine.SyncEngine
import io.github.marioalr.tpvca.sync.engine.SyncEngineConfig
import io.github.marioalr.tpvca.sync.engine.WriteEngine
import io.github.marioalr.tpvca.sync.link.MemoryFolioNumbers
import io.github.marioalr.tpvca.ui.phone.PhoneApp
import io.github.marioalr.tpvca.ui.phone.PhoneEnv
import io.github.marioalr.tpvca.ui.phone.PhoneStatus
import io.github.marioalr.tpvca.ui.phone.PhoneTab
import io.github.marioalr.tpvca.ui.phone.PhoneTags
import io.github.marioalr.tpvca.ui.phone.PhoneWorkspace
import io.github.marioalr.tpvca.ui.phone.PhoneWrites
import io.github.marioalr.tpvca.ui.tablet.ConfigSection
import io.github.marioalr.tpvca.ui.tablet.TabletApp
import io.github.marioalr.tpvca.ui.tablet.TabletChrome
import io.github.marioalr.tpvca.ui.tablet.TabletEnv
import io.github.marioalr.tpvca.ui.tablet.TabletTags
import io.github.marioalr.tpvca.ui.tablet.TabletWorkspace
import io.github.marioalr.tpvca.ui.theme.TpvcTheme
import io.github.marioalr.tpvca.ui.theme.TpvcThemes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

/**
 * K10: las revisiones de accesibilidad de Google (Accessibility Test Framework: etiquetas para TalkBack, tamaño de lo que se toca, contraste del texto y de las imágenes,
 * orden de lectura) sobre las pantallas principales del celular y de la tablet, en claro y en oscuro, con la letra normal y al 200 % y, en el celular, a 360 dp. Además,
 * que todo lo que se toca diga qué es. Junta lo que encuentra en todas las pantallas y lo dice junto al final. Datos inventados.
 *
 * Las del celular corren en un equipo angosto y las de la tablet en uno ancho (`./gradlew :app:connectedDebugAndroidTest` en los dos emuladores).
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "accessibility-device-test.db"
    private var database: TpvcaDatabase? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val assets by lazy { File(context.cacheDir, "membrete-accessibility") }
    private val problems = mutableListOf<String>()
    private var label = ""

    private val isTablet get() = context.resources.configuration.smallestScreenWidthDp >= 600

    @After
    fun close() {
        database?.close()
        context.deleteDatabase(name)
    }

    private fun store(): RoomSyncStore {
        context.deleteDatabase(name)
        val db = TpvcaDatabase.open(context, name, SqlCipher.factory(ByteArray(32) { 5 }))
        database = db
        val store = RoomSyncStore(db, assets)
        PhoneDemoSeed(store, Clinic.today(Instant.now())).fill()
        return store
    }

    private fun reader() = ClinicReader({ database!!.clinic() }, { assets })

    private fun waitFor(condition: () -> Boolean) {
        try {
            compose.waitUntil(20_000, condition)
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            // Lo que se veía, para saber por qué no llegó
            compose.onAllNodes(isRoot()).fetchSemanticsNodes().forEachIndexed { i, _ -> compose.onAllNodes(isRoot())[i].printToLog("A11Y-TIMEOUT") }
            throw e
        }
    }

    private fun waitTag(tag: String) = waitFor { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun waitText(text: String) = waitFor { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    /** El tema, la letra y, en el celular, el ancho de la prueba. */
    private fun show(dark: Boolean, fontScale: Float, size: DpSize?, content: @Composable () -> Unit) {
        compose.setContent {
            val override = if (size != null) DeviceConfigurationOverride.FontScale(fontScale) then DeviceConfigurationOverride.ForcedSize(size) else DeviceConfigurationOverride.FontScale(fontScale)
            DeviceConfigurationOverride(override) {
                TpvcTheme(if (dark) TpvcThemes.oscuro else TpvcThemes.claro) { content() }
            }
        }
        compose.waitForIdle()
    }

    /** Revisa la pantalla de ahora: lo de ATF y que todo lo que se toca diga qué es. No se detiene: lo junta para decirlo al final. */
    private fun check(screen: String, besidePanel: String? = null) {
        compose.waitForIdle()
        // Solo aquí: con las revisiones siempre puestas, cada toque las corre y se detiene en la primera
        val validator = AccessibilityValidator().setRunChecksFromRootView(true).setCaptureScreenshots(true).setThrowExceptionFor(AccessibilityCheckResultType.ERROR)
        // Un panel al lado (la agenda de la tablet) tapa a medias lo de atrás, que sigue a la mano: de lo tapado, Compose da a ATF solo la parte que se ve (una orilla de 2 dp, un
        // pedazo de 29 dp). Eso no cuenta. Lo que se toca del propio panel sí se revisa, y lo que está lejos del panel también
        besidePanel?.let { tag ->
            val node = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
            val offset = node.positionOnScreen - node.positionInWindow
            val panel = node.boundsInWindow.translate(offset)
            val own = descendants(node).filter { it.config.getOrNull(SemanticsActions.OnClick) != null }.map { it.boundsInWindow.translate(offset) }
            validator.setSuppressingResultMatcher(object : TypeSafeMatcher<AccessibilityViewCheckResult>() {
                override fun matchesSafely(result: AccessibilityViewCheckResult): Boolean {
                    if (result.sourceCheckClass != TouchTargetSizeCheck::class.java) return false
                    val b = result.element?.boundsInScreen ?: return false
                    return coveredByPanel(androidx.compose.ui.geometry.Rect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat()), panel, own)
                }

                override fun describeTo(description: Description) {
                    description.appendText("lo de atrás tapado por el panel $tag")
                }
            })
        }
        compose.enableAccessibilityChecks(validator)
        val roots = compose.onAllNodes(isRoot())
        for (i in 0 until roots.fetchSemanticsNodes().size) {
            try {
                roots[i].tryPerformAccessibilityChecks()
            } catch (e: Exception) {
                if (e.javaClass.simpleName != "AccessibilityViewCheckException") throw e
                problems += "$label · $screen: ${e.message}"
            }
        }
        compose.disableAccessibilityChecks()
        for (root in compose.onAllNodes(isRoot(), useUnmergedTree = false).fetchSemanticsNodes()) unlabeled(root, screen)
    }

    /**
     * El contraste de un texto medido en la pantalla: el fondo es el color que más se repite en su recuadro y la letra, el que más contrasta con él. ATF no lo marca como
     * error en todos los casos (un gris sobre el papel claro del ticket pasó); esto sí. Lo de abajo de 4.5:1 se junta con lo demás.
     */
    private fun textContrast(screen: String, text: String, within: String) {
        val nodes = compose.onAllNodes(hasText(text, substring = true) and hasAnyAncestor(hasTestTag(within)), useUnmergedTree = true)
        // El primero que se ve (con tamaño): uno que quedó fuera del recuadro no se puede medir
        val index = nodes.fetchSemanticsNodes().indexOfFirst { it.size.width > 0 && it.size.height > 0 }
        if (index < 0) {
            problems += "$label · $screen: no se encontró «$text» para medir su contraste"
            return
        }
        // A la vista: lo que quedó abajo del papel se desplaza hasta verse
        runCatching { nodes[index].performScrollTo() }
        compose.waitForIdle()
        val visible = nodes.fetchSemanticsNodes()[index].boundsInWindow
        if (visible.width < 1f || visible.height < 1f) {
            problems += "$label · $screen: «$text» no se ve en la pantalla para medir su contraste"
            return
        }
        val image = nodes[index].captureToImage().asAndroidBitmap()
        val counts = HashMap<Int, Int>()
        for (x in 0 until image.width) for (y in 0 until image.height) counts.merge(image.getPixel(x, y) or 0xFF000000.toInt(), 1, Int::plus)
        val background = counts.maxBy { it.value }.key
        val ratio = counts.keys.maxOf { contrast(it, background) }
        if (ratio < 4.5) problems += "$label · $screen: «$text» se lee a ${"%.2f".format(ratio)}:1 sobre su fondo"
    }

    private fun contrast(a: Int, b: Int): Double {
        fun lum(c: Int): Double {
            fun ch(v: Int): Double = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
            return 0.2126 * ch((c shr 16) and 0xFF) + 0.7152 * ch((c shr 8) and 0xFF) + 0.0722 * ch(c and 0xFF)
        }
        val la = lum(a)
        val lb = lum(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> = node.children.flatMap { listOf(it) + descendants(it) }

    /**
     * Lo de atrás tapado a medias por [panel]: no es ninguno de los controles del panel ([own]) y toca el panel (lo cruza o queda pegado a su orilla, a menos de 8 px). Lo que
     * está lejos del panel y lo del propio panel se revisan completos.
     */
    private fun coveredByPanel(element: androidx.compose.ui.geometry.Rect, panel: androidx.compose.ui.geometry.Rect, own: List<androidx.compose.ui.geometry.Rect>): Boolean {
        val tolerance = 1f
        val isOwn = own.any { element.left >= it.left - tolerance && element.right <= it.right + tolerance && element.top >= it.top - tolerance && element.bottom <= it.bottom + tolerance }
        val touches = element.right > panel.left - 8f && element.left < panel.right + 8f && element.bottom > panel.top - 8f && element.top < panel.bottom + 8f
        return !isOwn && touches
    }

    @Test
    fun soloSeIgnoraLoDeAtrasTapadoPorElPanel() {
        val r = { l: Float, t: Float, rr: Float, b: Float -> androidx.compose.ui.geometry.Rect(l, t, rr, b) }
        val panel = r(1600f, 72f, 2560f, 1600f)
        val own = listOf(r(1960f, 80f, 2040f, 176f), r(1640f, 200f, 1760f, 330f))
        // Lo de atrás: la orilla que se ve arriba del panel y un pedazo tapado
        assertTrue(coveredByPanel(r(2194f, 68f, 2512f, 72f), panel, own))
        assertTrue(coveredByPanel(r(1982f, 397f, 2264f, 455f), panel, own))
        // Los botones del propio panel, aunque estén chicos, se revisan
        assertTrue(!coveredByPanel(r(1970f, 100f, 2030f, 150f), panel, own))
        assertTrue(!coveredByPanel(r(1640f, 200f, 1760f, 330f), panel, own))
        // Lo que está a la izquierda, lejos del panel
        assertTrue(!coveredByPanel(r(100f, 68f, 400f, 72f), panel, own))
        // En una tablet angosta el panel ocupa todo el ancho: sus controles se revisan
        val wide = r(0f, 72f, 1200f, 1900f)
        assertTrue(!coveredByPanel(r(10f, 300f, 200f, 380f), wide, listOf(r(0f, 290f, 600f, 390f))))
    }

    /** Lo que se toca y está habilitado tiene un nombre que TalkBack lee (su texto o su descripción). */
    private fun unlabeled(node: SemanticsNode, screen: String) {
        val c = node.config
        val clickable = c.getOrNull(SemanticsActions.OnClick) != null && c.getOrNull(SemanticsProperties.Disabled) == null
        if (clickable) {
            val text = c.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()
            val description = c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty()
            val editable = c.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
            if (text.isBlank() && description.isBlank() && editable.isBlank() && c.getOrNull(SemanticsProperties.IsEditable) != true) {
                problems += "$label · $screen: algo que se toca sin nombre para TalkBack (${c.getOrNull(SemanticsProperties.TestTag) ?: "sin etiqueta de prueba"})"
            }
        }
        for (child in node.children) unlabeled(child, screen)
    }

    private fun report() {
        assertTrue("Lo que encontró la revisión de accesibilidad:\n" + problems.joinToString("\n\n"), problems.isEmpty())
    }

    // ------------------------------------------------------------------ celular

    private fun phoneFlow(dark: Boolean, fontScale: Float, width: Int) {
        assumeTrue("Las del celular corren en un equipo angosto", !isTablet)
        label = "celular ${if (dark) "oscuro" else "claro"}, letra ${(fontScale * 100).toInt()} %, $width dp"
        val store = store()
        phoneDemoExtras(store, notice = true)
        store.apply(StoreChanges(outboxClearedUpTo = Long.MAX_VALUE))
        val env = PhoneEnv(reader(), SharedDocuments(context), labFiles = null)
        val workspace = PhoneWorkspace(env, PhoneWrites({ WriteEngine(store, { "celular" }, MemoryFolioNumbers(), { null }) }, { LocalSide(store) }, { true }, {}), scope)
        show(dark, fontScale, DpSize(width.dp, 780.dp)) {
            PhoneApp(env, workspace, PhoneStatus("Sin conexión: se muestra lo último que bajó.", false, Instant.now().minusSeconds(300), false, "Consultorio"), 0L, {}, {})
        }
        waitText("Próximas")
        check("Inicio")
        compose.onNodeWithTag(PhoneTags.FAB).performClick()
        waitText("Buscar paciente")
        check("Inicio, menú +")
        compose.onNodeWithTag(PhoneTags.FAB).performClick()

        compose.onNodeWithTag(PhoneTags.HINT_NEGOCIO).performScrollTo().performClick()
        waitTag(PhoneTags.NEGOCIO)
        waitText("En el tiempo")
        check("Negocio")
        compose.onNodeWithText("Corte de caja").performScrollTo().performClick()
        waitTag(PhoneTags.CORTE)
        check("Corte de caja")
        // El ticket (papel: claro también en oscuro) y su PDF en la vista del documento
        compose.onAllNodes(hasText("TKT-", substring = true) and hasClickAction())[0].performScrollTo().performClick()
        waitText("Compartir ticket")
        waitText("TOTAL")
        check("Ticket")
        textContrast("Ticket", "TOTAL", PhoneTags.TICKET_PAPER)
        compose.onNodeWithText("Ver PDF").performClick()
        waitTag(PhoneTags.PREVIEW)
        waitFor { compose.onAllNodes(hasContentDescription("página 1 de", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        check("Vista del PDF del ticket")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        compose.onNodeWithTag(PhoneTags.tab(PhoneTab.Pacientes)).performClick()
        waitText("Paciente Ana Ejemplo Ruiz")
        check("Pacientes")
        compose.onNodeWithText("Paciente Ana Ejemplo Ruiz").performClick()
        waitTag(PhoneTags.FICHA)
        waitText("Alergias")
        check("Ficha, Resumen")
        for (tab in listOf("Consultas", "Recetas", "Laboratorio")) {
            compose.onNodeWithText(tab).performClick()
            compose.waitForIdle()
            check("Ficha, $tab")
        }

        compose.onNodeWithTag(PhoneTags.tab(PhoneTab.Agenda)).performClick()
        waitTag(PhoneTags.appointmentActions("10:30"))
        check("Agenda")
        // Como TalkBack (la acción del nodo): con la letra al 200 % el botón «Agendar cita» flota encima de la tarjeta hasta que se desplaza
        compose.onNodeWithTag(PhoneTags.appointmentActions("10:30")).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        waitTag(PhoneTags.APPOINTMENT_SHEET)
        check("Agenda, acciones de una cita")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag(PhoneTags.SCHEDULE).performClick()
        waitTag(PhoneTags.SCHEDULE_FORM)
        check("Agendar cita")
        compose.onNodeWithTag(PhoneTags.SCHEDULE_CLOSE).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(PhoneTags.tab(PhoneTab.Inventario)).performClick()
        waitTag(PhoneTags.PRODUCT_SEARCH)
        check("Inventario")
        // La lista es perezosa: con la letra grande, Tempra no se ve sin buscarlo
        compose.onNodeWithTag(PhoneTags.PRODUCT_SEARCH).performTextInput("Tempra")
        waitText("Tempra 500 mg tabletas")
        compose.onNodeWithText("Tempra 500 mg tabletas").performClick()
        waitTag(PhoneTags.PRODUCTO)
        check("Producto")
        report()
    }

    @Test
    fun celularClaro() = phoneFlow(dark = false, fontScale = 1f, width = 412)

    @Test
    fun celularOscuro() = phoneFlow(dark = true, fontScale = 1f, width = 412)

    @Test
    fun celularClaroAl200En360() = phoneFlow(dark = false, fontScale = 2f, width = 360)

    @Test
    fun celularOscuroAl200En360() = phoneFlow(dark = true, fontScale = 2f, width = 360)

    // ------------------------------------------------------------------ tablet

    private fun tabletFlow(dark: Boolean, fontScale: Float) {
        assumeTrue("Las de la tablet corren en un equipo ancho", isTablet)
        label = "tablet ${if (dark) "oscura" else "clara"}, letra ${(fontScale * 100).toInt()} %"
        val store = store()
        tabletDemoExtras(store)
        val env = TabletEnv(
            PhoneEnv(reader(), SharedDocuments(context), labFiles = null),
            writes = { WriteEngine(store, { "tablet" }, MemoryFolioNumbers(), { null }) },
            local = { LocalSide(store) },
            reapplySaved = { SyncEngine(NoCloud, store, SyncEngineConfig("tablet")).reapplySaved() },
            quickSync = { true },
            noteLocalWrite = {},
            refreshNow = {},
            isOnline = { false },
            syncBeforeImport = { null },
        )
        val workspace = TabletWorkspace(env, scope)
        val ana = store.select("Patients", listOf("SyncId", "RecordNumber")).first { it["RecordNumber"] == "EXP-2026-014" }["SyncId"] as String
        show(dark, fontScale, null) {
            TabletApp(workspace, TabletChrome(PhoneStatus(null, false, Instant.now(), false, "Consultorio"), 0L, "0.1.0", {}, {}))
        }
        waitTag(TabletTags.HUB)
        check("Inicio")
        compose.runOnUiThread { workspace.goPatients() }
        waitTag(TabletTags.HOME)
        check("Pacientes")
        compose.runOnUiThread { workspace.openAgenda() }
        waitTag(TabletTags.AGENDA)
        check("Agenda", besidePanel = TabletTags.AGENDA)
        compose.runOnUiThread { workspace.closeAgenda() }
        compose.runOnUiThread { workspace.openChart(ana) }
        waitTag(TabletTags.CHART_PRIMARY)
        check("Ficha")
        compose.onNodeWithTag(TabletTags.CHART_PRIMARY).performClick()
        waitTag(TabletTags.CONSULTATION)
        waitFor { workspace.consultation.account != null }
        check("Consulta")
        compose.runOnUiThread { workspace.enterPointOfSale() }
        waitTag(TabletTags.POS)
        check("Punto de venta")
        compose.onNodeWithTag(TabletTags.POS_CATALOG).performClick()
        waitTag(TabletTags.POS_SHEET)
        check("Punto de venta, hoja del catálogo")
        compose.runOnUiThread { workspace.back() }
        // Una venta cobrada: el ticket es papel, claro también en el tema oscuro
        compose.onNodeWithTag(TabletTags.POS_SEARCH).performTextInput("Ibuprofeno")
        compose.onNodeWithTag(TabletTags.POS_SEARCH).performImeAction()
        waitFor { workspace.pos.session.cart.hasItems }
        compose.onNodeWithTag(TabletTags.CHECKOUT_CHARGE).performScrollTo().performClick()
        waitTag(TabletTags.POS_RECEIPT)
        waitText("1 × ")
        check("Ticket de la venta")
        // El papel del ticket: cada tono de su letra se lee sobre el papel claro, también con la app en oscuro
        textContrast("Ticket de la venta", "1 × ", TabletTags.POS_RECEIPT)
        textContrast("Ticket de la venta", "Folio", TabletTags.POS_RECEIPT)
        textContrast("Ticket de la venta", "Gracias por su preferencia", TabletTags.POS_RECEIPT)
        compose.runOnUiThread { workspace.pos.closeReceipt() }
        for (section in listOf(ConfigSection.Dashboard, ConfigSection.Inventory, ConfigSection.Business)) {
            compose.runOnUiThread { workspace.enterConfig(section) }
            compose.waitForIdle()
            check("Configuración, ${section.title}")
        }
        // Un panel lateral (producto nuevo) y la hoja de la compra sugerida
        compose.runOnUiThread { workspace.enterConfig(ConfigSection.Inventory) }
        compose.runOnUiThread { workspace.inventory.newProduct() }
        waitTag(TabletTags.PRODUCT_EDITOR)
        check("Configuración, panel de producto nuevo")
        compose.runOnUiThread { workspace.back() }
        compose.waitForIdle()
        compose.runOnUiThread { workspace.goHub() }
        compose.runOnUiThread { workspace.inventory.openSuggested() }
        waitTag(TabletTags.SUGGESTED)
        check("Inicio, compra sugerida")
        compose.runOnUiThread { workspace.inventory.closeSuggested() }
        compose.runOnUiThread { workspace.goSync() }
        waitTag(TabletTags.SYNC)
        check("Sincronización")
        report()
    }

    @Test
    fun tabletClara() = tabletFlow(dark = false, fontScale = 1f)

    @Test
    fun tabletOscura() = tabletFlow(dark = true, fontScale = 1f)

    @Test
    fun tabletClaraAl200() = tabletFlow(dark = false, fontScale = 2f)

    @Test
    fun tabletOscuraAl200() = tabletFlow(dark = true, fontScale = 2f)
}
