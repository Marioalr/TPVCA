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
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.accessibility.disableAccessibilityChecks
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import com.google.android.apps.common.testing.accessibility.framework.integrations.espresso.AccessibilityValidator
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
    private fun check(screen: String) {
        compose.waitForIdle()
        // Solo aquí: con las revisiones siempre puestas, cada toque las corre y se detiene en la primera
        compose.enableAccessibilityChecks(AccessibilityValidator().setRunChecksFromRootView(true).setCaptureScreenshots(true).setThrowExceptionFor(AccessibilityCheckResultType.ERROR))
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
        check("Agenda")
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
        for (section in listOf(ConfigSection.Dashboard, ConfigSection.Inventory, ConfigSection.Business)) {
            compose.runOnUiThread { workspace.enterConfig(section) }
            compose.waitForIdle()
            check("Configuración, ${section.title}")
        }
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
