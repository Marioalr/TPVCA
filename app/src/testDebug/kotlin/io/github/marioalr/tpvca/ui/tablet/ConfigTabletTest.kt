package io.github.marioalr.tpvca.ui.tablet

import android.content.Context
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.marioalr.tpvca.data.RoomSyncStore
import io.github.marioalr.tpvca.data.TpvcaDatabase
import io.github.marioalr.tpvca.data.clinic.ClinicReader
import io.github.marioalr.tpvca.dev.NoCloud
import io.github.marioalr.tpvca.dev.PhoneDemoSeed
import io.github.marioalr.tpvca.dev.tabletDemoExtras
import io.github.marioalr.tpvca.documents.SharedDocuments
import io.github.marioalr.tpvca.domain.Clinic
import io.github.marioalr.tpvca.sync.engine.LocalSide
import io.github.marioalr.tpvca.sync.engine.LocalWriter
import io.github.marioalr.tpvca.sync.engine.SyncEngine
import io.github.marioalr.tpvca.sync.engine.SyncEngineConfig
import io.github.marioalr.tpvca.sync.engine.WriteEngine
import io.github.marioalr.tpvca.sync.link.MemoryFolioNumbers
import io.github.marioalr.tpvca.ui.phone.PhoneEnv
import io.github.marioalr.tpvca.ui.phone.PhoneStatus
import io.github.marioalr.tpvca.ui.theme.TpvcTheme
import io.github.marioalr.tpvca.ui.theme.TpvcThemes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant

/** Configuración de la tablet (K9b, la segunda mitad): el panel, «Negocio», Servicios con sus insumos y «Nuevo servicio» del punto de venta, sobre Room y el motor de escritura de verdad, con datos inventados. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w1280dp-h800dp-xlarge-land")
class ConfigTabletTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: TpvcaDatabase
    private lateinit var store: RoomSyncStore
    private lateinit var workspace: TabletWorkspace
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var quickGate: CompletableDeferred<Unit>? = null
    private var quickSyncs = 0
    private val onMainThread = java.util.concurrent.atomic.AtomicInteger()

    private fun checkThread() {
        if (android.os.Looper.getMainLooper().isCurrentThread) onMainThread.incrementAndGet()
    }

    @Before
    fun open() {
        database = Room.inMemoryDatabaseBuilder(context, TpvcaDatabase::class.java).allowMainThreadQueries().build()
        val assets = File(context.cacheDir, "membrete-configuracion")
        store = RoomSyncStore(database, assets)
        PhoneDemoSeed(store, Clinic.today(Instant.now())).fill()
        tabletDemoExtras(store)
        val folios = MemoryFolioNumbers()
        workspace = TabletWorkspace(
            TabletEnv(
                PhoneEnv(ClinicReader({ checkThread(); database.clinic() }, { assets }), SharedDocuments(context), labFiles = null),
                writes = { checkThread(); WriteEngine(store, { "tablet" }, folios, { null }) },
                local = { checkThread(); LocalSide(store) },
                reapplySaved = { SyncEngine(NoCloud, store, SyncEngineConfig("tablet")).reapplySaved() },
                quickSync = {
                    quickSyncs++
                    quickGate?.await()
                    true
                },
                noteLocalWrite = {},
                refreshNow = {},
                isOnline = { false },
            ),
            scope,
        )
    }

    @After
    fun close() {
        // Ni la base ni el motor en el hilo principal
        assertEquals(0, onMainThread.get())
        database.close()
    }

    private fun show() {
        compose.setContent {
            TpvcTheme(TpvcThemes.claro) { TabletApp(workspace, TabletChrome(PhoneStatus(null, false, Instant.now(), false, "Consultorio"), 0L, "0.1.0", {}, {})) }
        }
        compose.waitForIdle()
    }

    private fun waitFor(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (true) {
            compose.waitForIdle()
            if (condition()) return
            if (System.currentTimeMillis() > end) throw AssertionError("No se cumplió la condición a tiempo")
            compose.mainClock.advanceTimeBy(100)
            org.robolectric.shadows.ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS)
            Thread.sleep(10)
        }
    }

    private fun waitText(text: String) = waitFor { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun waitTag(tag: String) = waitFor { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun gone(tag: String) = waitFor { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }

    private fun field(name: String, text: String) {
        compose.onNodeWithTag(TabletTags.field(name)).performScrollTo().performTextClearance()
        compose.onNodeWithTag(TabletTags.field(name)).performTextInput(text)
    }

    private fun services() = store.select("ClinicalServices", listOf("SyncId", "Code", "Name", "Price", "IsActive", "Category"))

    private fun defaults(service: String) = store.select("ServiceDefaultProducts", listOf("SyncId", "ClinicalServiceSyncId", "ProductSyncId", "Quantity")).filter { it["ClinicalServiceSyncId"] == service }

    private fun enterConfig() {
        show()
        compose.onNodeWithTag(TabletTags.HUB_CONFIG).performClick()
        waitTag(TabletTags.DASHBOARD)
        waitFor { workspace.reports.dashboard != null }
    }

    // ------------------------------------------------------------------ el panel

    @Test
    fun configuracionAbreEnElPanelConLasCifrasDeLaPcYSusBotonesLlevanALaSeccion() {
        enterConfig()
        assertEquals(ConfigSection.Dashboard, workspace.inventory.section)
        val m = workspace.reports.dashboard!!.data.metrics
        assertTrue(m.totalProducts > 0)
        compose.onNodeWithText("Ventas de hoy", substring = false).assertExists()
        waitText("${m.totalProducts} productos · ")
        // El resumen del menú, con el formato C0 de la PC
        waitText("Ventas de hoy $")
        compose.onNodeWithText("Ver lotes").performScrollTo().performClick()
        waitTag(TabletTags.BATCHES)
        assertEquals(ConfigSection.Batches, workspace.inventory.section)
    }

    // ------------------------------------------------------------------ Negocio

    @Test
    fun negocioFiltraPorTipoYCambiaDePeriodoSinPisarseConLoQueLlegaTarde() {
        enterConfig()
        compose.onNodeWithTag(TabletTags.configSection("Business")).performClick()
        waitTag(TabletTags.BUSINESS_SUMMARY)
        val all = workspace.reports.business!!
        assertNull(all.type)
        assertEquals("Este mes", io.github.marioalr.tpvca.domain.business.BusinessReportRules.periodName(workspace.reports.periodKind))
        // Un toque en un renglón de «Por sección» filtra; otro lo quita
        val first = all.byType.firstOrNull()
        if (first != null) {
            compose.onNodeWithTag(TabletTags.businessType(first.name)).performScrollTo().performClick()
            waitFor { workspace.reports.business?.type == first.type }
            compose.onNodeWithTag(TabletTags.businessType(first.name)).performScrollTo().performClick()
            waitFor { workspace.reports.business?.type == null }
        }
        // Dos periodos seguidos: lo que llega es del último
        compose.runOnIdle {
            workspace.reports.choosePeriod("Hoy")
            workspace.reports.choosePeriod("Este año")
        }
        waitFor { !workspace.reports.businessLoading }
        val shown = workspace.reports.business!!
        assertEquals(workspace.today().withDayOfYear(1), shown.period.from)
        // Personalizado espera «Aplicar» y revisa las fechas
        compose.runOnIdle { workspace.reports.choosePeriod("Personalizado") }
        waitTag(TabletTags.BUSINESS_APPLY)
        compose.runOnIdle { workspace.reports.customFromText = "31/02/2026" }
        compose.onNodeWithTag(TabletTags.BUSINESS_APPLY).performClick()
        waitText("Escriba las dos fechas completas (dd/mm/aaaa).")
    }

    // ------------------------------------------------------------------ Servicios

    @Test
    fun unServicioNuevoEsperaLaSincronizacionRapidaSeGuardaUnaVezConSusInsumos() {
        enterConfig()
        compose.onNodeWithTag(TabletTags.configSection("Services")).performScrollTo().performClick()
        waitTag(TabletTags.SERVICES)
        waitFor { workspace.services.catalog != null }
        waitText("servicios activos")
        compose.onNodeWithTag(TabletTags.SERVICE_NEW).performClick()
        waitTag(TabletTags.SERVICE_EDITOR)
        // El siguiente código: uno más que el mayor (SERV-022 en los datos inventados)
        assertEquals("SERV-023", workspace.services.editor!!.code)
        field("servicio-nombre", "Retiro de puntos")
        field("servicio-precio", "180")
        val product = workspace.services.products().first()
        compose.runOnIdle {
            val e = workspace.services.editor!!
            e.selectedProduct = product
            e.newQuantityText = "2"
            workspace.services.addSupply(e)
            e.selectedProduct = product
            workspace.services.addSupply(e)
        }
        assertEquals(3, workspace.services.editor!!.supplies.single().quantity)
        quickGate = CompletableDeferred()
        compose.onNodeWithTag(TabletTags.SERVICE_SAVE).performClick()
        compose.onNodeWithTag(TabletTags.SERVICE_SAVE).assertIsNotEnabled()
        compose.runOnIdle { workspace.services.save(workspace.services.editor!!) }
        assertEquals(1, quickSyncs)
        quickGate!!.complete(Unit)
        gone(TabletTags.SERVICE_EDITOR)
        waitText("Servicio guardado: Retiro de puntos · $180.00")
        val saved = services().filter { it["Name"] == "Retiro de puntos" }
        assertEquals(1, saved.size)
        assertEquals("SERV-023", saved.single()["Code"])
        assertEquals(3L, defaults(saved.single()["SyncId"] as String).single()["Quantity"])
    }

    @Test
    fun elCodigoRepetidoSeDiceYUnaEdicionNoEsperaLaNube() {
        enterConfig()
        compose.onNodeWithTag(TabletTags.configSection("Services")).performScrollTo().performClick()
        waitFor { workspace.services.catalog != null }
        compose.onNodeWithTag(TabletTags.SERVICE_SEARCH).performTextInput("SERV-020")
        compose.onNodeWithTag(TabletTags.serviceEdit("SERV-020")).performScrollTo().performClick()
        waitTag(TabletTags.SERVICE_EDITOR)
        field("servicio-codigo", "serv-021")
        compose.onNodeWithTag(TabletTags.SERVICE_SAVE).performClick()
        waitTag(TabletTags.SERVICE_ERROR)
        compose.onNodeWithText("El código SERV-021 ya lo usa otro servicio.").assertExists()
        field("servicio-codigo", "SERV-020")
        field("servicio-precio", "95.5")
        compose.onNodeWithTag(TabletTags.SERVICE_SAVE).performClick()
        gone(TabletTags.SERVICE_EDITOR)
        assertEquals(0, quickSyncs)
        assertEquals("95.5", services().single { it["Code"] == "SERV-020" }["Price"])
    }

    @Test
    fun desactivarYEliminarPreguntanConElTextoDeLaPcYUnBorradoEnOtroEquipoDejaElPanelSinGuardar() {
        enterConfig()
        compose.onNodeWithTag(TabletTags.configSection("Services")).performScrollTo().performClick()
        waitFor { workspace.services.catalog != null }
        compose.onNodeWithTag(TabletTags.SERVICE_SEARCH).performTextInput("SERV-02")
        compose.onNodeWithTag(TabletTags.serviceToggle("SERV-021")).performScrollTo().performClick()
        waitText("Nebulización ya no se ofrece al cobrar")
        assertEquals(0L, services().single { it["Code"] == "SERV-021" }["IsActive"])
        // Desactivado: solo con «Mostrar desactivados»
        waitFor { compose.onAllNodesWithTag(TabletTags.serviceRow("SERV-021")).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag(TabletTags.SERVICE_INACTIVE).performClick()
        waitTag(TabletTags.serviceRow("SERV-021"))
        compose.onNodeWithTag(TabletTags.serviceDelete("SERV-021")).performScrollTo().performClick()
        waitText("¿Eliminar definitivamente el servicio «Nebulización» (SERV-021)?")
        compose.onNodeWithTag(TabletTags.CONFIRM_YES).performClick()
        waitText("Servicio eliminado: Nebulización")
        assertTrue(services().none { it["Code"] == "SERV-021" })
        // Otro equipo elimina el que está abierto: el panel sigue, sin «Guardar»
        compose.onNodeWithTag(TabletTags.serviceEdit("SERV-022")).performScrollTo().performClick()
        waitTag(TabletTags.SERVICE_EDITOR)
        val id = services().single { it["Code"] == "SERV-022" }["SyncId"] as String
        compose.runOnIdle { LocalWriter(store, { "pc" }).delete("ClinicalServices", id) }
        compose.runOnIdle { workspace.changed() }
        waitTag(TabletTags.SERVICE_GONE)
        compose.onNodeWithTag(TabletTags.SERVICE_SAVE).assertIsNotEnabled()
        // Atrás cierra el panel antes de salir de Configuración
        compose.runOnIdle { workspace.back() }
        gone(TabletTags.SERVICE_EDITOR)
        assertEquals(TabletPage.Config, workspace.page)
    }

    // ------------------------------------------------------------------ el punto de venta

    @Test
    fun nuevoServicioDelPuntoDeVentaSeGuardaYQuedaElegido() {
        show()
        compose.onNodeWithTag(TabletTags.HUB_POS).performClick()
        compose.runOnIdle { workspace.pos.openSheet(PosSheet.Services) }
        waitFor { workspace.pos.catalog != null }
        compose.onNodeWithTag(TabletTags.POS_SERVICE_NEW).performClick()
        waitTag(TabletTags.POS_NEW_SERVICE_SHEET)
        waitFor { workspace.services.posEditor?.code == "SERV-023" }
        assertEquals("300.00", workspace.services.posEditor!!.priceText)
        compose.onNodeWithTag(TabletTags.SERVICE_SAVE).performClick()
        waitText("El nombre del servicio es obligatorio.")
        field("servicio-nombre", "Certificado médico")
        compose.onNodeWithTag(TabletTags.SERVICE_SAVE).performClick()
        gone(TabletTags.POS_NEW_SERVICE_SHEET)
        assertEquals(1, quickSyncs)
        val saved = services().single { it["Name"] == "Certificado médico" }
        assertEquals("Consulta", saved["Category"])
        waitFor { workspace.pos.selectedService()?.syncId == saved["SyncId"] }
        // «Eliminar servicio» pregunta con el texto de su hoja
        compose.onNodeWithTag(TabletTags.POS_SERVICE_DELETE).performScrollTo().performClick()
        waitText("¿Eliminar el servicio 'Certificado médico' (SERV-023) del catálogo?")
        compose.onNodeWithTag(TabletTags.CONFIRM_YES).performClick()
        waitText("Servicio 'Certificado médico' eliminado.")
        assertTrue(services().none { it["Name"] == "Certificado médico" })
        // Atrás cierra lo de encima: primero la hoja de servicios, después el punto de venta
        compose.runOnIdle { workspace.back() }
        assertNull(workspace.pos.sheet)
    }

    /** Revisión de #12: un «Nuevo servicio» que falla después de salir del punto de venta avisa abajo y se suelta: nunca vuelve a salir encima de otra pantalla ni se queda con el error escondido. */
    @Test
    fun unNuevoServicioQueFallaDespuesDeSalirAvisaYNoVuelve() {
        show()
        compose.onNodeWithTag(TabletTags.HUB_POS).performClick()
        compose.runOnIdle { workspace.pos.openSheet(PosSheet.Services) }
        waitFor { workspace.pos.catalog != null }
        compose.onNodeWithTag(TabletTags.POS_SERVICE_NEW).performClick()
        waitFor { workspace.services.posEditor?.code == "SERV-023" }
        field("servicio-nombre", "Certificado de ejemplo")
        quickGate = CompletableDeferred()
        compose.onNodeWithTag(TabletTags.SERVICE_SAVE).performClick()
        waitFor { quickSyncs == 1 }
        // Mientras espera la nube, otro equipo ya usó SERV-023 y el médico se va al inicio
        compose.runOnIdle {
            LocalWriter(store, { "pc" }).create("ClinicalServices", io.github.marioalr.tpvca.sync.rules.SyncIds.random(), mapOf("Code" to "SERV-023", "Name" to "Otro", "Category" to "Consulta", "Price" to "1", "IsActive" to true))
            workspace.goHub()
        }
        quickGate!!.complete(Unit)
        waitFor { workspace.toast?.text?.contains("El código SERV-023 ya lo usa otro servicio.") == true }
        assertTrue(workspace.toast!!.warning)
        assertNull(workspace.services.posEditor)
        assertTrue(services().none { it["Name"] == "Certificado de ejemplo" })
        // Al volver a los servicios del punto de venta, la hoja no reaparece
        compose.onNodeWithTag(TabletTags.HUB_POS).performClick()
        compose.runOnIdle { workspace.pos.openSheet(PosSheet.Services) }
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithTag(TabletTags.POS_NEW_SERVICE_SHEET).fetchSemanticsNodes().isEmpty())
    }
}
