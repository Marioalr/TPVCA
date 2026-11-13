package io.github.marioalr.tpvca.ui.tablet

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
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
import io.github.marioalr.tpvca.dev.catalogDuplicateDemo
import io.github.marioalr.tpvca.dev.tabletDemoExtras
import io.github.marioalr.tpvca.documents.SharedDocuments
import io.github.marioalr.tpvca.domain.Clinic
import io.github.marioalr.tpvca.sync.engine.LocalSide
import io.github.marioalr.tpvca.sync.engine.LocalWriter
import io.github.marioalr.tpvca.sync.engine.StoreChanges
import io.github.marioalr.tpvca.sync.engine.SyncEngine
import io.github.marioalr.tpvca.sync.engine.SyncEngineConfig
import io.github.marioalr.tpvca.sync.engine.WriteEngine
import io.github.marioalr.tpvca.sync.link.MemoryFolioNumbers
import io.github.marioalr.tpvca.sync.rules.SyncIds
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant

/** Configuración de la tablet (K9b): Productos, Lotes y Kardex con edición, «Ingresar pedido» y la compra sugerida, sobre Room y el motor de escritura de verdad, con datos inventados. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w1280dp-h800dp-xlarge-land")
class InventoryTabletTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: TpvcaDatabase
    private lateinit var store: RoomSyncStore
    private lateinit var workspace: TabletWorkspace
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var quickGate: CompletableDeferred<Unit>? = null
    private var quickSyncs = 0
    private var localWrites = 0
    private val onMainThread = java.util.concurrent.atomic.AtomicInteger()

    private fun checkThread() {
        if (android.os.Looper.getMainLooper().isCurrentThread) onMainThread.incrementAndGet()
    }

    @Before
    fun open() {
        database = Room.inMemoryDatabaseBuilder(context, TpvcaDatabase::class.java).allowMainThreadQueries().build()
        val assets = File(context.cacheDir, "membrete-inventario")
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
                noteLocalWrite = { localWrites++ },
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

    private fun products() = store.select("Products", listOf("SyncId", "Code", "Name", "CurrentStock", "IsActive", "UnitsPerPackage"))

    private fun product(code: String) = products().first { it["Code"] == code }

    private fun movements(productSyncId: String) = store.select("InventoryMovements", listOf("SyncId", "ProductSyncId", "MovementType", "Delta", "Reason", "BatchSyncId")).filter { it["ProductSyncId"] == productSyncId }

    private fun field(name: String, text: String) {
        compose.onNodeWithTag(TabletTags.field(name)).performScrollTo().performTextClearance()
        compose.onNodeWithTag(TabletTags.field(name)).performTextInput(text)
    }

    /** Entra por la tarjeta del inicio y espera la lista. */
    private fun enterConfig() {
        show()
        compose.onNodeWithTag(TabletTags.HUB_CONFIG).performClick()
        waitTag(TabletTags.PRODUCTS)
        waitFor { workspace.inventory.snapshot != null }
    }

    // ------------------------------------------------------------------ el menú

    @Test
    fun configuracionAbreProductosConElMenuDeLaPcYAtrasRegresa() {
        enterConfig()
        assertEquals("El menú y el título", 2, compose.onAllNodesWithText("Productos").fetchSemanticsNodes().size)
        waitText("con stock bajo")
        // Lo que no es de esta versión se ve y lo dice
        assertTrue(compose.onAllNodesWithText(NEXT_VERSION_TEXT).fetchSemanticsNodes().size >= 8)
        compose.onNodeWithTag(TabletTags.configSection("Business")).performClick()
        assertEquals(ConfigSection.Inventory, workspace.inventory.section)
        assertFalse("Configuración no es Pacientes", workspace.isPatientsSection())
        // El buscador de ajustes lleva a la sección
        compose.onNodeWithTag(TabletTags.CONFIG_SEARCH).performTextInput("caducidad")
        waitText("Registrar un lote")
        compose.onNodeWithText("Registrar un lote").performClick()
        waitTag(TabletTags.BATCHES)
        compose.runOnIdle { workspace.back() }
        assertEquals(TabletPage.Hub, workspace.page)
    }

    // ------------------------------------------------------------------ alta y edición

    @Test
    fun unAltaEsperaLaSincronizacionRapidaSeGuardaUnaVezYQuedaAbierta() {
        enterConfig()
        compose.onNodeWithTag(TabletTags.PRODUCT_NEW).performClick()
        waitTag(TabletTags.PRODUCT_EDITOR)
        field("codigo", "MED-777")
        field("nombre", "Naproxeno 250 mg")
        field("existencias", "4")
        field("precio", "45.50")
        field("costo", "20")
        waitText("Ganancia: $25.50 por unidad (56 % del precio).")
        quickGate = CompletableDeferred()
        compose.onNodeWithTag(TabletTags.PRODUCT_SAVE).performClick()
        // El segundo toque mientras espera la nube no hace nada
        compose.onNodeWithTag(TabletTags.PRODUCT_SAVE).assertIsNotEnabled()
        compose.runOnIdle { workspace.inventory.saveProduct() }
        assertEquals(1, quickSyncs)
        quickGate!!.complete(Unit)
        waitTag(TabletTags.PRODUCT_SUCCESS)
        compose.onNodeWithText("\"Naproxeno 250 mg\" guardado. Puede registrar el siguiente.").assertIsDisplayed()
        val saved = products().filter { it["Code"] == "MED-777" }
        assertEquals(1, saved.size)
        assertEquals(4L, saved.single()["CurrentStock"])
        assertEquals("Inventario inicial de alta de producto", movements(saved.single()["SyncId"] as String).single()["Reason"])
        assertTrue(localWrites > 0)
        // Queda abierto y vacío para el siguiente; el mismo código ya no se acepta
        assertEquals("", workspace.inventory.editor!!.code)
        field("codigo", " med-777 ")
        field("nombre", "Otro")
        compose.onNodeWithTag(TabletTags.PRODUCT_SAVE).performClick()
        waitTag(TabletTags.PRODUCT_ERROR)
        compose.onNodeWithText("El código 'med-777' ya está registrado en el inventario. Debe ingresar un código diferente.").assertIsDisplayed()
        assertEquals(1, products().count { (it["Code"] as String).trim().equals("MED-777", ignoreCase = true) })
    }

    @Test
    fun unConteoQuedaEnElKardexYSinTocarLaExistenciaNoSeRevierteLoVendido() {
        enterConfig()
        val ibuprofeno = product("MED-003")
        compose.onNodeWithTag(TabletTags.productRow("MED-003")).performClick()
        waitTag(TabletTags.PRODUCT_EDITOR)
        // Mientras el formulario está abierto se venden 5 en otro lado
        store.apply(StoreChanges(writes = listOf(io.github.marioalr.tpvca.sync.engine.RowWrite("Products", ibuprofeno["SyncId"] as String, mapOf("CurrentStock" to 30L), isNew = false))))
        field("precio", "60")
        compose.onNodeWithTag(TabletTags.PRODUCT_SAVE).performClick()
        gone(TabletTags.PRODUCT_EDITOR)
        assertEquals("La venta de mientras no se revierte", 30L, product("MED-003")["CurrentStock"])
        assertTrue(movements(ibuprofeno["SyncId"] as String).none { it["Reason"] == "Ajuste manual de existencias desde la ficha del producto" })
        // Ahora sí un conteo
        waitFor { workspace.inventory.snapshot?.products?.first { it.code == "MED-003" }?.currentStock == 30 }
        compose.onNodeWithTag(TabletTags.productRow("MED-003")).performClick()
        waitTag(TabletTags.PRODUCT_EDITOR)
        field("existencias", "28")
        compose.onNodeWithTag(TabletTags.PRODUCT_SAVE).performClick()
        gone(TabletTags.PRODUCT_EDITOR)
        assertEquals(28L, product("MED-003")["CurrentStock"])
        assertEquals(-2L, movements(ibuprofeno["SyncId"] as String).single { it["Reason"] == "Ajuste manual de existencias desde la ficha del producto" }["Delta"])
        compose.onNodeWithTag(TabletTags.configSection("Movements")).performClick()
        waitTag(TabletTags.KARDEX)
        waitText("Ajuste manual de existencias desde la ficha del producto")
    }

    @Test
    fun unProductoQueSeBorraConElFormularioAbiertoYaNoSeGuarda() {
        enterConfig()
        compose.onNodeWithTag(TabletTags.productRow("MED-004")).performClick()
        waitTag(TabletTags.PRODUCT_EDITOR)
        val omeprazol = product("MED-004")["SyncId"] as String
        store.apply(StoreChanges(deletes = mapOf("InventoryMovements" to store.select("InventoryMovements", listOf("SyncId", "ProductSyncId")).filter { it["ProductSyncId"] == omeprazol }.map { it["SyncId"] as String }.toSet(), "ProductBatches" to store.select("ProductBatches", listOf("SyncId", "ProductSyncId")).filter { it["ProductSyncId"] == omeprazol }.map { it["SyncId"] as String }.toSet(), "Products" to setOf(omeprazol))))
        compose.runOnIdle { workspace.changed() }
        waitTag(TabletTags.PRODUCT_GONE)
        compose.onNodeWithTag(TabletTags.PRODUCT_SAVE).assertIsNotEnabled()
        assertFalse(products().any { it["SyncId"] == omeprazol })
    }

    // ------------------------------------------------------------------ entrada y consumo

    @Test
    fun unConsumoQueNoAlcanzaSeRegistraYSeAvisa() {
        enterConfig()
        val amoxicilina = product("MED-002")
        compose.onNodeWithTag(TabletTags.productMore("MED-002")).performClick()
        compose.onNodeWithText("Registrar consumo (sin cobro)").performClick()
        waitTag(TabletTags.QUICK_PANEL)
        compose.onNodeWithText("Consumo sin cobro").assertIsDisplayed()
        compose.onNodeWithTag(TabletTags.QUICK_QUANTITY).performTextClearance()
        compose.onNodeWithTag(TabletTags.QUICK_QUANTITY).performTextInput("9")
        compose.onNodeWithTag(TabletTags.QUICK_CONFIRM).performClick()
        gone(TabletTags.QUICK_PANEL)
        waitText("Existencias insuficientes: Amoxicilina 500 mg cápsulas (quedan 2, se cobran 9). Revise el inventario.")
        assertEquals(-7L, product("MED-002")["CurrentStock"])
        assertTrue(movements(amoxicilina["SyncId"] as String).filter { it["MovementType"] == 2L }.all { it["Reason"] == "Uso en consulta médica" })
    }

    @Test
    fun unaEntradaCambiaElContenidoYUnProductoDadoDeBajaNoDejaRegistrar() {
        enterConfig()
        compose.onNodeWithTag(TabletTags.productEntry("INS-002")).performClick()
        waitTag(TabletTags.QUICK_PANEL)
        compose.onNodeWithTag(TabletTags.field("contenido-entrada")).performTextInput("10")
        compose.onNodeWithTag(TabletTags.QUICK_CONFIRM).performClick()
        gone(TabletTags.QUICK_PANEL)
        assertEquals(90L, product("INS-002")["CurrentStock"])
        assertEquals(10L, product("INS-002")["UnitsPerPackage"])
        // Otro equipo lo da de baja con el panel abierto
        waitFor { workspace.inventory.snapshot?.products?.firstOrNull { it.code == "INS-002" }?.currentStock == 90 }
        compose.onNodeWithTag(TabletTags.productEntry("INS-002")).performClick()
        waitTag(TabletTags.QUICK_PANEL)
        LocalWriter(store, { "otro" }).edit("Products", product("INS-002")["SyncId"] as String, mapOf("IsActive" to false))
        compose.runOnIdle { workspace.changed() }
        waitTag(TabletTags.QUICK_GONE)
        compose.onNodeWithTag(TabletTags.QUICK_CONFIRM).assertIsNotEnabled()
        compose.runOnIdle { workspace.inventory.confirmQuick() }
        assertEquals(90L, product("INS-002")["CurrentStock"])
        // Salir de Configuración lo cierra
        compose.runOnIdle { workspace.goHub() }
        assertNull(workspace.inventory.quick)
    }

    @Test
    fun darDeBajaPreguntaConElTextoDeLaPc() {
        enterConfig()
        compose.onNodeWithTag(TabletTags.PRODUCT_SEARCH).performTextInput("venda")
        waitFor { workspace.inventory.products().size == 1 }
        compose.onNodeWithTag(TabletTags.productMore("INS-004")).performClick()
        compose.onNodeWithText("Dar de baja del catálogo").performClick()
        waitText("¿Dar de baja 'Venda elástica 10 cm con un nombre bastante largo para ver cómo se parte' (INS-004) del catálogo?")
        compose.onNodeWithTag(TabletTags.CONFIRM_YES).performClick()
        waitFor { product("INS-004")["IsActive"] == 0L }
        waitFor { compose.onAllNodesWithTag(TabletTags.productRow("INS-004")).fetchSemanticsNodes().isEmpty() }
    }

    // ------------------------------------------------------------------ lotes

    @Test
    fun registrarYQuitarUnLote() {
        enterConfig()
        compose.onNodeWithTag(TabletTags.configSection("Batches")).performClick()
        waitTag(TabletTags.BATCHES)
        compose.onNodeWithTag(TabletTags.BATCH_NEW).performClick()
        waitTag(TabletTags.BATCH_PANEL)
        compose.onNodeWithTag(TabletTags.field("lote-producto")).performClick()
        compose.onNodeWithTag(TabletTags.CHOICE_SEARCH).performTextInput("omeprazol")
        compose.onNodeWithTag(TabletTags.choice("Omeprazol 20 mg cápsulas · MED-004")).performClick()
        field("lote-numero", "L-PRUEBA")
        field("lote-piezas", "6")
        field("lote-caducidad", "05/2020")
        waitTag(TabletTags.BATCH_WARNING)
        compose.onNodeWithText("Esa fecha ya pasó: el lote entraría vencido.").assertIsDisplayed()
        field("lote-caducidad", "12/2099")
        val before = product("MED-004")["CurrentStock"] as Long
        compose.onNodeWithTag(TabletTags.BATCH_SAVE).performClick()
        gone(TabletTags.BATCH_PANEL)
        assertEquals(before + 6, product("MED-004")["CurrentStock"])
        waitTag(TabletTags.batchRow("L-PRUEBA"))
        // Quitar: la pregunta dice cuánto se descuenta
        compose.onNodeWithTag(TabletTags.batchRemove("L-PRUEBA")).performScrollTo().performClick()
        waitText("¿Eliminar el lote L-PRUEBA?")
        waitText("Se descontarán 6 pieza(s) restantes de las existencias de 'Omeprazol 20 mg cápsulas'.")
        compose.onNodeWithTag(TabletTags.CONFIRM_YES).performClick()
        waitFor { compose.onAllNodesWithTag(TabletTags.batchRow("L-PRUEBA")).fetchSemanticsNodes().isEmpty() }
        assertEquals(before, product("MED-004")["CurrentStock"])
    }

    @Test
    fun unLoteQueOtroEquipoYaCerroAvisaYNoDescuentaOtraVez() {
        enterConfig()
        val omeprazol = product("MED-004")["SyncId"] as String
        val lot = SyncIds.random()
        WriteEngine(store, { "tablet" }, MemoryFolioNumbers(), { null }).receiveBatch(lot, omeprazol, "L-OTRO", java.time.LocalDate.of(2099, 1, 31), 3, null, 1)
        // El cierre del otro equipo llegó; el lote cerrado todavía no
        LocalWriter(store, { "otro" }).create(
            "InventoryMovements", SyncIds.derived("close:", lot),
            mapOf("ProductSyncId" to omeprazol, "BatchSyncId" to lot, "MovementType" to "5", "Quantity" to "3", "Delta" to "-3", "Date" to "2026-01-02 10:00:00.0000000"),
        )
        compose.onNodeWithTag(TabletTags.configSection("Batches")).performClick()
        waitTag(TabletTags.batchRow("L-OTRO"))
        val stock = product("MED-004")["CurrentStock"]
        compose.onNodeWithTag(TabletTags.batchRemove("L-OTRO")).performScrollTo().performClick()
        waitText("Este lote ya se cerró en otro equipo: las existencias de 'Omeprazol 20 mg cápsulas' no cambiarán.")
        compose.onNodeWithTag(TabletTags.CONFIRM_YES).performClick()
        waitText("Este lote ya se había cerrado en otro equipo; revise las existencias de Omeprazol 20 mg cápsulas")
        assertEquals(stock, product("MED-004")["CurrentStock"])
    }

    // ------------------------------------------------------------------ «Ingresar pedido»

    private val email = listOf(
        "Detalles de la Compra", "Número de Orden: #900", "Producto\tSKU\tCantidad\tTotal",
        "OMEPRAZOL 20MG CAPSULAS\tWAN100\t2\t$50.00", "NUEVO PRODUCTO C/10 TABS\tABC123\t3\t$30.00",
        "Resumen de la Compra", "Subtotal\t$80.00", "IVA\t$0.00", "Total + IVA\t$80.00",
    ).joinToString("\n")

    @Test
    fun unPedidoSeLeeSeRevisaYEntraCompleto() {
        enterConfig()
        compose.onNodeWithTag(TabletTags.ORDER_OPEN).performClick()
        waitTag(TabletTags.ORDER_SHEET)
        compose.onNodeWithTag(TabletTags.ORDER_PASTE).performTextInput("nada")
        compose.onNodeWithTag(TabletTags.ORDER_READ).performClick()
        waitTag(TabletTags.ORDER_ERROR)
        compose.onNodeWithTag(TabletTags.ORDER_PASTE).performTextClearance()
        compose.onNodeWithTag(TabletTags.ORDER_PASTE).performTextInput(email)
        compose.onNodeWithTag(TabletTags.ORDER_READ).performClick()
        waitText("Pedido #900 · LUIN")
        // El primero se parece al Omeprazol del catálogo (misma marca y concentración): conviene revisarlo
        compose.onNodeWithText("2 productos · 1 del catálogo · 1 nuevos").assertIsDisplayed()
        compose.onNodeWithText("Parecido: revise").assertIsDisplayed()
        compose.onNodeWithText("Los 2 renglones suman $80.00, igual que el correo.").assertIsDisplayed()
        compose.onNodeWithTag(TabletTags.orderField(0, "producto")).performClick()
        compose.onNodeWithTag(TabletTags.CHOICE_SEARCH).performTextInput("omepra")
        compose.onNodeWithTag(TabletTags.choice("Omeprazol 20 mg cápsulas · MED-004")).performClick()
        waitText("Elegido a mano")
        compose.onNodeWithText("2 productos · 1 del catálogo · 1 nuevos").assertIsDisplayed()
        // Un lote sin caducidad no deja ingresar
        compose.onNodeWithTag(TabletTags.orderField(0, "lote")).performTextInput("L-900")
        waitText("Falta la caducidad del lote.")
        compose.onNodeWithTag(TabletTags.ORDER_CONFIRM).assertIsNotEnabled()
        compose.onNodeWithTag(TabletTags.orderField(0, "caducidad")).performTextInput("12/2099")
        val before = product("MED-004")["CurrentStock"] as Long
        quickSyncs = 0
        compose.onNodeWithTag(TabletTags.ORDER_CONFIRM).performClick()
        gone(TabletTags.ORDER_SHEET)
        waitText("Pedido #900 · LUIN: 2 productos ingresados; 1 nuevos sin precio de venta (complételos en Productos).")
        assertEquals("Da de alta un producto: espera la sincronización rápida", 1, quickSyncs)
        assertEquals(before + 2, product("MED-004")["CurrentStock"])
        assertEquals(3L, product("ABC123")["CurrentStock"])
        // Otra vez el mismo pedido: avisa que ya se ingresó y pregunta antes
        compose.onNodeWithTag(TabletTags.ORDER_OPEN).performClick()
        waitTag(TabletTags.ORDER_SHEET)
        compose.onNodeWithTag(TabletTags.ORDER_PASTE).performTextInput(email)
        compose.onNodeWithTag(TabletTags.ORDER_READ).performClick()
        waitTag(TabletTags.ORDER_DUPLICATE)
        waitText("Ingresarlo otra vez sumaría las existencias de nuevo.")
        assertEquals(2, compose.onAllNodesWithText("Llegó en un pedido anterior").fetchSemanticsNodes().size)
        compose.onNodeWithTag(TabletTags.ORDER_CONFIRM).performClick()
        waitText("¿Ingresarlo de todos modos?")
        compose.onNodeWithTag(TabletTags.CONFIRM_NO).performClick()
        compose.waitForIdle()
        assertEquals(3L, product("ABC123")["CurrentStock"])
        assertNotNull(workspace.inventory.order)
    }

    // ------------------------------------------------------------------ avisos, Kardex y compra sugerida

    @Test
    fun elAvisoDelCatalogoRepetidoSeVeYSeDescarta() {
        catalogDuplicateDemo(store)
        enterConfig()
        waitTag(TabletTags.CATALOG_NOTICE)
        waitText("Posible registro repetido en el catálogo con lo que llegó de otro equipo: producto «Jeringa 5 ml")
        compose.onNodeWithText("Entendido").performClick()
        gone(TabletTags.CATALOG_NOTICE)
        compose.runOnIdle { workspace.changed() }
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithTag(TabletTags.CATALOG_NOTICE).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun laCompraSugeridaSeAbreDelInicioYSeCierraAlIrse() {
        show()
        compose.onNodeWithTag(TabletTags.HUB_SUGGESTED).performClick()
        waitTag(TabletTags.SUGGESTED)
        compose.onNodeWithText("Lista Sugerida de Compras y Resurtido").assertIsDisplayed()
        waitText("Amoxicilina 500 mg cápsulas")
        compose.runOnIdle { workspace.back() }
        gone(TabletTags.SUGGESTED)
        assertEquals(TabletPage.Hub, workspace.page)
    }

    @Test
    fun atrasCierraElPanelAntesDeSalir() {
        enterConfig()
        compose.onNodeWithTag(TabletTags.PRODUCT_NEW).performClick()
        waitTag(TabletTags.PRODUCT_EDITOR)
        compose.runOnIdle { workspace.back() }
        gone(TabletTags.PRODUCT_EDITOR)
        assertEquals(TabletPage.Config, workspace.page)
        compose.runOnIdle { workspace.back() }
        assertEquals(TabletPage.Hub, workspace.page)
    }

    /** En vertical el menú y la lista caben. */
    @Test
    @Config(qualifiers = "w800dp-h1280dp-xlarge-port")
    fun enVerticalCabe() {
        enterConfig()
        compose.onNodeWithTag(TabletTags.productEntry("MED-003")).assertIsDisplayed()
        compose.onNodeWithTag(TabletTags.configSection("Movements")).performClick()
        waitTag(TabletTags.KARDEX)
        waitFor { workspace.inventory.kardex != null }
    }

    // ------------------------------------------------------------------ revisión independiente de K9b

    /** El resumen de Lotes y «Por caducar» cuentan de hoy a 60 días, como `ExpiringSoonCount` de la PC (antes de hoy + 61). */
    @Test
    fun porVencerCuentaSesentaDias() {
        enterConfig()
        val before = workspace.inventory.summaries!!.batches
        val omeprazol = product("MED-004")["SyncId"] as String
        val engine = WriteEngine(store, { "tablet" }, MemoryFolioNumbers(), { null })
        val today = workspace.today()
        engine.receiveBatch(SyncIds.random(), omeprazol, "L-60", today.plusDays(60), 1, null, 1)
        engine.receiveBatch(SyncIds.random(), omeprazol, "L-61", today.plusDays(61), 1, null, 1)
        compose.runOnIdle { workspace.changed() }
        waitFor { workspace.inventory.summaries?.batches != before }
        val soon = Regex("(\\d+) por vencer").find(before)!!.groupValues[1].toInt()
        assertTrue(workspace.inventory.summaries!!.batches.contains("${soon + 1} por vencer"))
        compose.runOnIdle { workspace.inventory.expiringOnly = true }
        compose.waitForIdle()
        assertTrue(workspace.inventory.products().any { it.code == "MED-004" })
    }

    /** Dos productos iguales (mismo código y nombre, de dos equipos sin red) con stock bajo: la lista sugerida no se cae por llaves repetidas. */
    @Test
    fun laCompraSugeridaConDosProductosIgualesNoSeCae() {
        val gasa = product("INS-001")
        val twin = "10000000-0000-4000-8000-00000000beef"
        val category = store.select("Products", listOf("SyncId", "CategorySyncId")).first { it["SyncId"] == gasa["SyncId"] }["CategorySyncId"]
        LocalWriter(store, { "otro" }).create("Products", twin, mapOf("Code" to "INS-001", "Name" to gasa["Name"], "CategorySyncId" to category, "IsActive" to true, "CurrentStock" to 1L, "MinStock" to 5L))
        show()
        compose.onNodeWithTag(TabletTags.HUB_SUGGESTED).performClick()
        waitTag(TabletTags.SUGGESTED)
        waitFor { compose.onAllNodesWithText("Gasa estéril 10x10 cm").fetchSemanticsNodes().size == 2 }
    }

    /** Otro equipo cambia el contenido por paquete de 10 a 12 con la entrada abierta y sin tocar el campo: se queda 12. */
    @Test
    fun unaEntradaNoPisaElContenidoQueCambioOtroEquipo() {
        val jeringa = product("INS-002")["SyncId"] as String
        store.apply(StoreChanges(writes = listOf(io.github.marioalr.tpvca.sync.engine.RowWrite("Products", jeringa, mapOf("UnitsPerPackage" to 10L), isNew = false))))
        enterConfig()
        compose.onNodeWithTag(TabletTags.productEntry("INS-002")).performClick()
        waitTag(TabletTags.QUICK_PANEL)
        assertEquals("10", workspace.inventory.quick!!.unitsText)
        LocalWriter(store, { "otro" }).edit("Products", jeringa, mapOf("UnitsPerPackage" to 12L))
        compose.onNodeWithTag(TabletTags.QUICK_CONFIRM).performClick()
        gone(TabletTags.QUICK_PANEL)
        assertEquals(12L, product("INS-002")["UnitsPerPackage"])
        assertEquals(90L, product("INS-002")["CurrentStock"])
    }

    /** Salir de Configuración mientras un alta espera la sincronización rápida no la tira: se guarda y se avisa. */
    @Test
    fun salirMientrasGuardaTerminaElGuardadoYLoDice() {
        enterConfig()
        compose.onNodeWithTag(TabletTags.PRODUCT_NEW).performClick()
        waitTag(TabletTags.PRODUCT_EDITOR)
        field("codigo", "MED-888")
        field("nombre", "Loratadina 10 mg")
        quickGate = CompletableDeferred()
        compose.onNodeWithTag(TabletTags.PRODUCT_SAVE).performClick()
        compose.waitForIdle()
        compose.runOnIdle { workspace.goHub() }
        assertNull(workspace.inventory.editor)
        quickGate!!.complete(Unit)
        waitText("\"Loratadina 10 mg\" guardado.")
        assertEquals(1, products().count { it["Code"] == "MED-888" })
    }
}
