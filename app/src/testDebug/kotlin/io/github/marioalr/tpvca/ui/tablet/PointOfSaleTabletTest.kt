package io.github.marioalr.tpvca.ui.tablet

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
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
import io.github.marioalr.tpvca.sync.engine.StoreChanges
import io.github.marioalr.tpvca.sync.engine.SyncEngine
import io.github.marioalr.tpvca.sync.engine.SyncEngineConfig
import io.github.marioalr.tpvca.sync.engine.WriteEngine
import io.github.marioalr.tpvca.sync.link.MemoryFolioNumbers
import io.github.marioalr.tpvca.sync.rules.PointOfSaleDraft
import io.github.marioalr.tpvca.sync.rules.consultation.AccountLines
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

/** El punto de venta de la tablet (K9) en la JVM, sobre Room y el motor de escritura de verdad, sin conexión y con datos inventados. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w1280dp-h800dp-xlarge-land")
class PointOfSaleTabletTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: TpvcaDatabase
    private lateinit var store: RoomSyncStore
    private lateinit var workspace: TabletWorkspace
    private val folios = MemoryFolioNumbers()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var quickGate: CompletableDeferred<Unit>? = null
    private var quickSyncs = 0
    private val onMainThread = java.util.concurrent.atomic.AtomicInteger()

    private fun checkThread() {
        if (android.os.Looper.getMainLooper().isCurrentThread) onMainThread.incrementAndGet()
    }

    private fun env(): TabletEnv {
        val assets = File(context.cacheDir, "membrete-venta")
        return TabletEnv(
            PhoneEnv(
                ClinicReader({
                    checkThread()
                    database.clinic()
                }, { assets }),
                SharedDocuments(context),
                labFiles = null,
            ),
            writes = {
                checkThread()
                WriteEngine(store, { "tablet" }, folios, { null })
            },
            local = {
                checkThread()
                LocalSide(store)
            },
            reapplySaved = { SyncEngine(NoCloud, store, SyncEngineConfig("tablet")).reapplySaved() },
            quickSync = {
                quickSyncs++
                quickGate?.await()
                true
            },
            noteLocalWrite = {},
            refreshNow = {},
            isOnline = { false },
        )
    }

    @Before
    fun open() {
        database = Room.inMemoryDatabaseBuilder(context, TpvcaDatabase::class.java).allowMainThreadQueries().build()
        store = RoomSyncStore(database, File(context.cacheDir, "membrete-venta"))
        PhoneDemoSeed(store, Clinic.today(Instant.now())).fill()
        tabletDemoExtras(store)
        workspace = TabletWorkspace(env(), scope)
    }

    @After
    fun close() {
        // Ni la base ni el motor en el hilo principal
        assertEquals(0, onMainThread.get())
        database.close()
    }

    private fun show(target: TabletWorkspace = workspace) {
        compose.setContent {
            TpvcTheme(TpvcThemes.claro) { TabletApp(target, TabletChrome(PhoneStatus(null, false, Instant.now(), false, "Consultorio"), 0L, "0.1.0", {}, {})) }
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

    private fun productId(code: String) = store.select("Products", listOf("SyncId", "Code")).first { it["Code"] == code }["SyncId"] as String

    private fun stock(code: String) = store.select("Products", listOf("Code", "CurrentStock")).first { it["Code"] == code }["CurrentStock"] as Long

    private fun sales() = store.select("Sales", listOf("SyncId", "Folio", "Total", "IsCancelled", "CustomerName"))

    /** Entra por la tarjeta del inicio y espera el catálogo. */
    private fun enter() {
        show()
        compose.onNodeWithTag(TabletTags.HUB_POS).performClick()
        waitTag(TabletTags.POS)
        waitFor { workspace.pos.catalog != null }
    }

    private fun type(text: String) {
        compose.onNodeWithTag(TabletTags.POS_SEARCH).performTextClearance()
        compose.onNodeWithTag(TabletTags.POS_SEARCH).performTextInput(text)
        waitFor { workspace.pos.suggestions.text == text }
    }

    private fun enterKey() = compose.onNodeWithTag(TabletTags.POS_SEARCH).performImeAction()

    private fun charge() = compose.onNodeWithTag(TabletTags.CHECKOUT_CHARGE).performScrollTo().performClick()

    // ------------------------------------------------------------------ entrar y salir

    @Test
    fun nuevaVentaAbreElPuntoDeVentaYAtrasRegresa() {
        enter()
        compose.onNodeWithText("Punto de venta").assertIsDisplayed()
        compose.onNodeWithText("Escriba para buscar").assertIsDisplayed()
        assertFalse("El punto de venta no es Pacientes", workspace.isPatientsSection())
        compose.runOnIdle { workspace.back() }
        assertEquals(TabletPage.Hub, workspace.page)
    }

    // ------------------------------------------------------------------ buscar, 3* y cobrar sin conexión

    @Test
    fun tresAsteriscoAgregaTresYSeCobraSinConexionConLaLetra() {
        enter()
        type("3*ibupro")
        compose.onNodeWithText("Enter agrega 3").assertIsDisplayed()
        assertEquals("Ibuprofeno 400 mg tabletas", workspace.pos.suggestions.products.first().product.name)
        enterKey()
        waitFor { workspace.pos.session.cart.items.size == 1 }
        assertEquals(3, workspace.pos.session.cart.items.single().quantity)
        assertEquals("El buscador queda listo para el siguiente", "", workspace.pos.search)
        val saleId = workspace.pos.saleSyncId
        charge()
        waitTag(TabletTags.POS_RECEIPT)
        compose.onNodeWithText("Venta cobrada").assertIsDisplayed()
        val sale = sales().single { it["SyncId"] == saleId }
        assertTrue("Sin conexión, con la letra del equipo: ${sale["Folio"]}", (sale["Folio"] as String).contains("-T1-"))
        assertEquals(32L, stock("MED-003"))
        assertEquals(1, quickSyncs)
        assertNull("La cuenta guardada se fue con la venta", store.meta(WriteEngine.SALE_DRAFT))
        assertFalse(workspace.pos.session.cart.hasItems)
        assertTrue("La siguiente cuenta lleva otro ticket", workspace.pos.saleSyncId != saleId)
        assertTrue(store.outbox().any { it.syncId == saleId })
        compose.onNodeWithTag(TabletTags.POS_RECEIPT_DONE).performClick()
        waitFor { workspace.pos.receipt == null }
    }

    /** Un inventario mal contado nunca impide cobrar: Tempra está en −3, se vende y queda en −4 con su aviso. */
    @Test
    fun conExistenciasEnNegativoSeCobraYSeAvisa() {
        enter()
        type("tempra")
        compose.onNodeWithTag(TabletTags.posSuggestion(0)).performClick()
        waitFor { workspace.pos.session.cart.hasItems }
        // El aviso junto al botón, sin bloquear
        waitText("revisar inventario")
        charge()
        waitTag(TabletTags.POS_RECEIPT)
        assertEquals(-4L, stock("MED-014"))
        assertTrue(workspace.toast!!.warning)
        assertTrue(workspace.toast!!.text, workspace.toast!!.text.contains("Venta cobrada"))
        assertTrue(workspace.toast!!.text, workspace.toast!!.text.contains("Revise el inventario"))
    }

    /** Dos toques en «Cobrar», el segundo mientras el primero espera a la nube: un solo ticket. */
    @Test
    fun dosToquesCobranUnaVez() {
        enter()
        type("omeprazol")
        enterKey()
        waitFor { workspace.pos.session.cart.hasItems }
        quickGate = CompletableDeferred()
        compose.runOnIdle {
            workspace.pos.charge()
            workspace.pos.charge()
        }
        compose.waitForIdle()
        quickGate!!.complete(Unit)
        waitTag(TabletTags.POS_RECEIPT)
        assertEquals(1, sales().count { (it["Folio"] as String).contains("-T1-") })
        assertEquals(49L, stock("MED-004"))
    }

    /** Cobrar otra vez con el mismo ticket (un reintento después de morir el proceso) lo encuentra y no descuenta de nuevo. */
    @Test
    fun laCuentaGuardadaSobreviveYNoCobraDosVeces() {
        enter()
        type("omeprazol")
        enterKey()
        waitFor { store.meta(WriteEngine.SALE_DRAFT) != null }
        val saved = PointOfSaleDraft.decode(store.meta(WriteEngine.SALE_DRAFT))!!
        assertEquals(workspace.pos.saleSyncId, saved.saleSyncId)
        assertEquals("Omeprazol 20 mg cápsulas", saved.items.single().name)

        // Android cierra la app: al volver, la cuenta sigue con el mismo ticket
        val again = TabletWorkspace(env(), scope)
        compose.runOnIdle { again.restoreDrafts() }
        waitFor { again.pos.session.cart.hasItems }
        assertEquals(saved.saleSyncId, again.pos.saleSyncId)
        compose.runOnIdle { again.pos.charge() }
        waitFor { again.pos.receipt != null }
        assertEquals(49L, stock("MED-004"))

        // Se cobró pero la cuenta guardada volvió (un guardado viejo): al abrir, sobra y se va sola
        store.apply(StoreChanges(meta = mapOf(WriteEngine.SALE_DRAFT to saved.encode())))
        val third = TabletWorkspace(env(), scope)
        compose.runOnIdle { third.restoreDrafts() }
        waitFor { store.meta(WriteEngine.SALE_DRAFT) == null }
        assertFalse(third.pos.session.cart.hasItems)
        assertEquals(49L, stock("MED-004"))
    }

    // ------------------------------------------------------------------ servicios

    @Test
    fun unServicioLlegaConSusInsumos() {
        enter()
        compose.onNodeWithTag(TabletTags.POS_SERVICES).performClick()
        waitTag(TabletTags.POS_SHEET)
        compose.onNodeWithTag(TabletTags.posService("SERV-020")).performClick()
        compose.onNodeWithTag(TabletTags.POS_SERVICE_ADD).performScrollTo().performClick()
        waitFor { workspace.pos.sheet == null }
        assertEquals(listOf("Aplicación de inyección", "Jeringa 5 ml con aguja"), workspace.pos.session.cart.items.map { it.name })
        assertEquals("Aplicación de inyección y 1 insumo(s) agregados a la venta", workspace.toast?.text)
    }

    // ------------------------------------------------------------------ tickets

    private fun localSale(folio: String, original: String? = null, total: String = "50.00", withItem: Boolean = true): String {
        val writer = LocalWriter(store, { "pc" })
        val id = io.github.marioalr.tpvca.sync.rules.SyncIds.random()
        writer.create(
            "Sales", id,
            mapOf("Folio" to folio, "OriginalFolio" to original, "LedgerVersion" to "1", "Total" to total, "Date" to io.github.marioalr.tpvca.sync.ProtocolDate.format(java.time.LocalDateTime.now()), "CustomerName" to "Cliente de prueba", "PatientName" to "Cliente de prueba", "PaymentMethod" to "Efectivo", "Cashier" to "Dra. Prueba"),
        )
        if (withItem) writer.create("SaleItems", io.github.marioalr.tpvca.sync.rules.SyncIds.random(), mapOf("SaleSyncId" to id, "IsService" to true, "Code" to "SERV-001", "ProductName" to "Consulta general", "Description" to "Consulta general", "Quantity" to "1", "UnitPrice" to total, "Subtotal" to total))
        return id
    }

    @Test
    fun losTicketsSeBuscanPorElFolioSinConexionYSeCancelanPorSuSyncId() {
        val offline = localSale("TKT-20261113-0040", original = "TKT-20261113-T2-0003")
        val twin = localSale("TKT-20261113-0040")
        enter()
        compose.onNodeWithTag(TabletTags.POS_TICKETS).performClick()
        waitTag(TabletTags.POS_TICKET_DETAIL)
        compose.onNodeWithTag(TabletTags.POS_TICKET_SEARCH).performTextInput("tkt-20261113-t2-0003")
        compose.onNodeWithTag(TabletTags.POS_TICKET_FIND).performClick()
        waitFor { workspace.pos.tickets.map { it.syncId } == listOf(offline) }
        compose.onNodeWithText("Folio sin conexión: TKT-20261113-T2-0003").assertIsDisplayed()

        compose.onNodeWithTag(TabletTags.POS_CANCEL).performClick()
        waitTag(TabletTags.POS_CANCEL_CONFIRM)
        compose.onNodeWithTag(TabletTags.POS_CANCEL_CONFIRM).performClick()
        waitFor { sales().first { it["SyncId"] == offline }["IsCancelled"] == 1L }
        assertEquals("El del mismo folio no se toca", 0L, sales().first { it["SyncId"] == twin }["IsCancelled"])
        waitText("cancelado: la mercancía volvió al inventario")
    }

    @Test
    fun unTicketConImporteYSinRenglonesPideSincronizar() {
        val half = localSale("TKT-20261113-0041", withItem = false)
        enter()
        compose.onNodeWithTag(TabletTags.POS_TICKETS).performClick()
        waitFor { workspace.pos.tickets.isNotEmpty() }
        compose.onNodeWithTag(TabletTags.posTicket(half)).performClick()
        compose.onNodeWithTag(TabletTags.POS_CANCEL).performClick()
        compose.onNodeWithTag(TabletTags.POS_CANCEL_CONFIRM).performClick()
        waitTag(TabletTags.POS_TICKET_STATUS)
        waitText("Sincronice primero")
        assertEquals(0L, sales().first { it["SyncId"] == half }["IsCancelled"])
    }

    /** La confirmación es del ticket que se preguntó: elegir otro la cierra; nunca cancela el otro. */
    @Test
    fun elegirOtroTicketCierraLaConfirmacion() {
        val first = localSale("TKT-20261113-0042")
        val second = localSale("TKT-20261113-0043")
        enter()
        compose.onNodeWithTag(TabletTags.POS_TICKETS).performClick()
        waitFor { workspace.pos.tickets.size >= 2 }
        compose.onNodeWithTag(TabletTags.posTicket(first)).performClick()
        compose.onNodeWithTag(TabletTags.POS_CANCEL).performClick()
        waitTag(TabletTags.POS_CANCEL_CONFIRM)
        compose.onNodeWithTag(TabletTags.posTicket(second)).performClick()
        compose.waitForIdle()
        assertNull(workspace.pos.cancelTarget)
        assertTrue(compose.onAllNodesWithTag(TabletTags.POS_CANCEL_CONFIRM).fetchSemanticsNodes().isEmpty())
        assertEquals(0L, sales().first { it["SyncId"] == second }["IsCancelled"])
    }

    /** Irse del punto de venta cierra sus hojas; una lectura que llega tarde no las vuelve a llenar. Atrás cierra primero lo de encima. */
    @Test
    fun loAbiertoNoSeQuedaEncimaDeOtraPantalla() {
        localSale("TKT-20261113-0044")
        enter()
        compose.onNodeWithTag(TabletTags.POS_TICKETS).performClick()
        waitTag(TabletTags.POS_SHEET)
        compose.runOnIdle { workspace.back() }
        assertNull(workspace.pos.sheet)
        assertEquals(TabletPage.Pos, workspace.page)
        compose.onNodeWithTag(TabletTags.POS_CASH_CUT).performClick()
        compose.runOnIdle { workspace.goPatients() }
        waitTag(TabletTags.HOME)
        assertNull(workspace.pos.sheet)
        assertNull("La lectura del corte que llegó tarde se tiró", workspace.pos.cashCut)
        compose.runOnIdle { workspace.enterPointOfSale() }
        waitTag(TabletTags.POS)
        assertTrue(compose.onAllNodesWithTag(TabletTags.POS_SHEET).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun elCorteDelDiaSumaLoCobrado() {
        var before = java.math.BigDecimal.ZERO
        kotlin.concurrent.thread { before = io.github.marioalr.tpvca.domain.business.CashCutRules.compute(workspace.env.phone.reader.salesOfDay(workspace.today()), workspace.today()).totalCash }.join()
        localSale("TKT-20261113-0045", total = "120.00")
        enter()
        compose.onNodeWithTag(TabletTags.POS_CASH_CUT).performClick()
        waitFor { workspace.pos.cashCut != null }
        assertEquals(0, before.add(java.math.BigDecimal("120.00")).compareTo(workspace.pos.cashCut!!.cut.totalCash))
        compose.onNodeWithTag(TabletTags.POS_CUT_NEXT).assertExists()
        compose.onNodeWithTag(TabletTags.POS_CUT_PREVIOUS).performClick()
        waitFor { workspace.pos.cashCut?.date == workspace.today().minusDays(1) }
    }

    // ------------------------------------------------------------------ a la consulta

    /** «Pasar a la cuenta de la consulta»: todo a la cuenta, la venta se vacía y su cuenta guardada se va en la misma transacción. */
    @Test
    fun laVentaPasaALaCuentaDeLaConsulta() {
        val ana = store.select("Patients", listOf("SyncId", "RecordNumber")).first { it["RecordNumber"] == "EXP-2026-014" }["SyncId"] as String
        workspace.openChart(ana)
        show()
        waitTag(TabletTags.CHART_PRIMARY)
        compose.onNodeWithTag(TabletTags.CHART_PRIMARY).performClick()
        waitTag(TabletTags.CONSULTATION)
        waitFor { workspace.consultation.account != null }
        compose.runOnIdle { workspace.goHub() }
        compose.onNodeWithTag(TabletTags.HUB_POS).performClick()
        waitFor { workspace.pos.catalog != null }
        type("2*ibupro")
        enterKey()
        waitFor { store.meta(WriteEngine.SALE_DRAFT) != null }
        compose.onNodeWithTag(TabletTags.POS_TRANSFER).performClick()
        waitFor { !workspace.pos.session.cart.hasItems }
        val line = workspace.consultation.draft!!.items.single { it.lineKind == AccountLines.PRODUCT }
        assertEquals(productId("MED-003"), line.catalogSyncId)
        assertEquals(2, line.quantity)
        assertNull(store.meta(WriteEngine.SALE_DRAFT))
        assertEquals("Nada se cobró todavía", 35L, stock("MED-003"))
        assertNotNull(workspace.toast)
        assertTrue(workspace.toast!!.text.contains("agregados a la cuenta de la consulta de"))
    }
}
