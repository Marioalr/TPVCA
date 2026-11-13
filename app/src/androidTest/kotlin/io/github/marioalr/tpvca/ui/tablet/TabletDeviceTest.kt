package io.github.marioalr.tpvca.ui.tablet

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.marioalr.tpvca.data.RoomSyncStore
import io.github.marioalr.tpvca.data.SqlCipher
import io.github.marioalr.tpvca.data.TpvcaDatabase
import io.github.marioalr.tpvca.data.clinic.ClinicReader
import io.github.marioalr.tpvca.dev.NoCloud
import io.github.marioalr.tpvca.dev.PhoneDemoSeed
import io.github.marioalr.tpvca.dev.tabletDemoExtras
import io.github.marioalr.tpvca.documents.SharedDocuments
import io.github.marioalr.tpvca.domain.Clinic
import io.github.marioalr.tpvca.domain.patients.DateMaskText
import io.github.marioalr.tpvca.domain.patients.PatientForm
import io.github.marioalr.tpvca.domain.patients.PatientFormRules
import io.github.marioalr.tpvca.sync.engine.LocalSide
import io.github.marioalr.tpvca.sync.engine.SyncEngine
import io.github.marioalr.tpvca.sync.engine.SyncEngineConfig
import io.github.marioalr.tpvca.sync.engine.WriteEngine
import io.github.marioalr.tpvca.sync.link.MemoryFolioNumbers
import io.github.marioalr.tpvca.ui.phone.PhoneEnv
import io.github.marioalr.tpvca.ui.phone.PhoneStatus
import io.github.marioalr.tpvca.ui.theme.TpvcTheme
import io.github.marioalr.tpvca.ui.theme.TpvcThemes
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
import java.io.File
import java.time.Instant
import java.time.LocalDate

/** Lo que en la JVM no se puede creer: SQLCipher de verdad, las expresiones en ICU y los menús emergentes con toques de verdad. Datos inventados. */
@RunWith(AndroidJUnit4::class)
class TabletDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "tablet-device-test.db"
    private lateinit var database: TpvcaDatabase
    private lateinit var store: RoomSyncStore
    private lateinit var workspace: TabletWorkspace
    private var saves = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val assets by lazy { File(context.cacheDir, "membrete-device") }

    private fun env() = TabletEnv(
        PhoneEnv(ClinicReader({ database.clinic() }, { assets }), SharedDocuments(context), labFiles = null),
        writes = { WriteEngine(store, { "tablet" }, MemoryFolioNumbers(), { null }) },
        local = { LocalSide(store) },
        reapplySaved = { SyncEngine(NoCloud, store, SyncEngineConfig("tablet")).reapplySaved() },
        quickSync = { true },
        noteLocalWrite = { saves++ },
        refreshNow = {},
        isOnline = { false },
        syncBeforeImport = { null },
    )

    @Before
    fun open() {
        context.deleteDatabase(name)
        database = TpvcaDatabase.open(context, name, SqlCipher.factory(ByteArray(32) { 7 }))
        store = RoomSyncStore(database, assets)
        PhoneDemoSeed(store, Clinic.today(Instant.now())).fill()
        tabletDemoExtras(store)
        workspace = TabletWorkspace(env(), scope)
    }

    @After
    fun close() {
        database.close()
        context.deleteDatabase(name)
    }

    private fun show() {
        compose.setContent {
            TpvcTheme(TpvcThemes.claro) { TabletApp(workspace, TabletChrome(PhoneStatus(null, false, Instant.now(), false, "Consultorio"), 0L, "0.1.0", {}, {})) }
        }
        compose.waitForIdle()
    }

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(15_000, condition)

    private fun waitText(text: String) = waitFor { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun waitTag(tag: String) = waitFor { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun ana() = store.select("Patients", listOf("SyncId", "RecordNumber")).first { it["RecordNumber"] == "EXP-2026-014" }["SyncId"] as String

    @Test
    fun lasReglasDelFormularioEnIcu() {
        val today = LocalDate.of(2026, 11, 4)
        assertTrue(PatientFormRules.isValidEmail("ana.lopez@correo.com.mx"))
        assertFalse(PatientFormRules.isValidEmail("ana@correo"))
        assertEquals(LocalDate.of(1992, 3, 14), PatientFormRules.tryParseBirthDate("14/03/1992", today))
        assertEquals(LocalDate.of(1992, 3, 14), PatientFormRules.tryParseBirthDate("14031992", today))
        assertNull(PatientFormRules.tryParseBirthDate("31/02/1992", today))
        assertEquals("María José", PatientForm.fromSearch("maría josé garcía lópez", "X").firstName)
        assertEquals("08/08/2008", DateMaskText.paste("", 0, 0, "2008-08-08")?.text)
        assertEquals("551-234-5678", PatientForm().withPhone("+52 55 1234 5678").phone)
    }

    @Test
    fun unAltaConToquesSeGuardaEnLaBaseCifradaYSuBorradorSeVa() {
        workspace.goPatients()
        show()
        compose.onNodeWithTag(TabletTags.NEW_PATIENT).performClick()
        waitTag(TabletTags.FORM)
        compose.onNodeWithTag(TabletTags.field("FirstName")).performTextInput("Laura")
        compose.onNodeWithTag(TabletTags.field("LastName")).performTextInput("Prueba")
        compose.onNodeWithTag(TabletTags.field("BirthDate")).performTextInput("14/03/1990")
        waitText("36 años")
        compose.onNodeWithTag("tablet-gender-Femenino").performScrollTo().performClick()
        waitFor { store.meta(LocalSide.DRAFT_PREFIX + LocalSide.PATIENT_FORM) != null }
        compose.onNodeWithText("Alergias y enfermedades crónicas").performScrollTo().performClick()
        waitTag(TabletTags.field("Denied"))
        compose.onNodeWithTag(TabletTags.field("Denied")).performScrollTo().performClick()
        compose.onNodeWithTag(TabletTags.FORM_SAVE).performClick()
        waitFor { workspace.page is TabletPage.Chart }
        waitTag(TabletTags.ALLERGY)
        compose.onNodeWithText("Alergias negadas").assertIsDisplayed()
        val row = store.select("Patients", listOf("FullName", "RecordNumber", "Age", "Allergies", "FieldStampsJson")).first { it["FullName"] == "Laura Prueba" }
        assertTrue(row["RecordNumber"] as String, (row["RecordNumber"] as String).contains("-T1-"))
        assertEquals(36L, row["Age"])
        assertEquals(1, store.outbox().size)
        assertEquals(1, saves)
        waitFor { store.meta(LocalSide.DRAFT_PREFIX + LocalSide.PATIENT_FORM) == null }
    }

    /** K9: el prefijo `3*` y `RankProducts` en ICU, una venta sin conexión con toques sobre SQLCipher (Tempra en −3 queda en −4) y su cancelación por `SyncId`. */
    @Test
    fun unaVentaSinConexionEnNegativoYSuCancelacion() {
        assertEquals(3 to "para", io.github.marioalr.tpvca.domain.search.CatalogSearchRules.parseQuantityPrefix(" 3 * para"))
        assertEquals(1 to "٣*para", io.github.marioalr.tpvca.domain.search.CatalogSearchRules.parseQuantityPrefix("٣*para"))
        show()
        compose.onNodeWithTag(TabletTags.HUB_POS).performClick()
        waitFor { workspace.pos.catalog != null }
        compose.onNodeWithTag(TabletTags.POS_SEARCH).performTextInput("2*tempra")
        waitTag(TabletTags.posSuggestion(0))
        compose.onNodeWithText("Enter agrega 2").assertIsDisplayed()
        compose.onNodeWithTag(TabletTags.posSuggestion(0)).performClick()
        waitFor { workspace.pos.session.cart.hasItems }
        compose.onNodeWithTag(TabletTags.CHECKOUT_CHARGE).performScrollTo().performClick()
        waitTag(TabletTags.POS_RECEIPT)
        val sale = store.select("Sales", listOf("SyncId", "Folio", "LedgerVersion")).single { (it["Folio"] as String).contains("-T1-") }
        fun tempra() = store.select("Products", listOf("Code", "CurrentStock")).first { it["Code"] == "MED-014" }["CurrentStock"]
        assertEquals(-5L, tempra())
        compose.onNodeWithTag(TabletTags.POS_RECEIPT_NOTICE).assertIsDisplayed()
        compose.onNodeWithTag(TabletTags.POS_RECEIPT_DONE).performClick()
        waitFor { workspace.pos.receipt == null }
        compose.onNodeWithTag(TabletTags.POS_TICKETS).performClick()
        waitTag(TabletTags.posTicket(sale["SyncId"] as String))
        compose.onNodeWithTag(TabletTags.posTicket(sale["SyncId"] as String)).performClick()
        compose.onNodeWithTag(TabletTags.POS_CANCEL).performClick()
        waitTag(TabletTags.POS_CANCEL_CONFIRM)
        compose.onNodeWithTag(TabletTags.POS_CANCEL_CONFIRM).performScrollTo().performClick()
        try {
            waitFor { store.select("Sales", listOf("SyncId", "IsCancelled")).first { it["SyncId"] == sale["SyncId"] }["IsCancelled"] == 1L }
        } catch (e: Throwable) {
            val pos = workspace.pos
            throw AssertionError("No se canceló: estado=${pos.ticketStatus} confirmar=${pos.cancelTarget?.folio} cancelando=${pos.cancelling} elegido=${pos.selectedTicket?.folio} aviso=${workspace.toast?.text} info=${workspace.info}", e)
        }
        assertEquals(-3L, tempra())
    }

    @Test
    fun laBaseCifradaSeVuelveAAbrirConElBorrador() {
        val local = LocalSide(store)
        local.saveDraft(LocalSide.PATIENT_FORM, "lo escrito")
        database.close()
        database = TpvcaDatabase.open(context, name, SqlCipher.factory(ByteArray(32) { 7 }))
        store = RoomSyncStore(database, assets)
        assertEquals("lo escrito", LocalSide(store).draft(LocalSide.PATIENT_FORM))
    }

    @Test
    fun elMenuDeLaFichaAbreLaEdicion() {
        workspace.openChart(ana())
        show()
        waitTag(TabletTags.MORE)
        compose.onNodeWithTag(TabletTags.MORE).performClick()
        waitText("Editar expediente")
        compose.onNodeWithText("Ver receta").assertExists()
        compose.onNodeWithText("Editar expediente").performClick()
        waitTag(TabletTags.FORM)
        assertNotNull(workspace.form)
        assertEquals("Paciente Ana", workspace.form!!.form.firstName)
    }

    @Test
    fun elMenuDeUnaCitaConfirmaYCambiaDeHorario() {
        val day = workspace.today().plusDays(1)
        workspace.goPatients()
        workspace.openAgenda(day)
        show()
        waitTag(TabletTags.appointmentMenu("16:30"))
        compose.onNodeWithTag(TabletTags.appointmentMenu("16:30")).performScrollTo().performClick()
        waitText("Marcar como confirmada")
        compose.onNodeWithText("Atender ahora").assertExists()
        compose.onNodeWithText("Marcar como confirmada").performClick()
        waitFor { store.outbox().isNotEmpty() }
        waitText("Confirmada")
        compose.onNodeWithTag(TabletTags.appointmentMenu("16:30")).performScrollTo().performClick()
        waitText("Cambiar fecha u hora")
        compose.onNodeWithText("Cambiar fecha u hora").performClick()
        waitText("Elija el nuevo horario para")
        compose.onNodeWithTag(TabletTags.slot("11:00")).performScrollTo().performClick()
        waitFor { workspace.moving == null }
        val row = workspace.env.phone.reader.appointmentsBetween(day, day.plusDays(1)).single().appointment
        assertEquals("11:00", row.time)
        assertEquals("Programada", row.status)
    }

    @Test
    fun laPulsacionLargaDaElMenuDeLaPc() {
        workspace.goPatients()
        show()
        waitText("Última:")
        compose.onNodeWithText("Paciente Sofía Ejemplo Cruz · ", substring = true, useUnmergedTree = false).assertDoesNotExist()
        compose.onAllNodesWithText("EXP-2025-188", substring = true)[0].performTouchInput { longClick() }
        waitText("Eliminar expediente")
        compose.onNodeWithText("Abrir expediente").assertExists()
        waitTag(TabletTags.HOME)
    }

    // ------------------------------------------------------------------ K9b, segunda mitad

    /** Un .xlsx mínimo hecho a mano: MEDICAMENTOS con dos renglones (uno de cadenas compartidas) y RECETA con la imagen del encabezado recortada a la mitad. */
    private fun workbook(header: ByteArray): ByteArray {
        val main = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        val rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        val pkg = "http://schemas.openxmlformats.org/package/2006/relationships"
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            fun put(name: String, text: String) = put(name, text.toByteArray())
            put("xl/workbook.xml", """<workbook xmlns="$main" xmlns:r="$rel"><sheets><sheet name="MEDICAMENTOS" sheetId="1" r:id="rId1"/><sheet name="RECETA" sheetId="2" r:id="rId2"/></sheets></workbook>""")
            put("xl/_rels/workbook.xml.rels", """<Relationships xmlns="$pkg"><Relationship Id="rId1" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Target="worksheets/sheet2.xml"/></Relationships>""")
            put("xl/sharedStrings.xml", """<sst xmlns="$main"><si><t>Medicamento</t></si><si><r><t>Naproxeno </t></r><r><t>250 mg tabletas</t></r></si></sst>""")
            put(
                "xl/worksheets/sheet1.xml",
                """<worksheet xmlns="$main"><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>Clave</t></is></c><c r="B1" t="s"><v>0</v></c></row>""" +
                    """<row r="2"><c r="A2" t="inlineStr"><is><t>NAP-1</t></is></c><c r="B2" t="s"><v>1</v></c></row><row r="4"><c r="B4" t="inlineStr"><is><t>Crema de ejemplo</t></is></c></row></sheetData></worksheet>""",
            )
            put("xl/worksheets/sheet2.xml", """<worksheet xmlns="$main" xmlns:r="$rel"><sheetData/><drawing r:id="rIdD"/></worksheet>""")
            put("xl/worksheets/_rels/sheet2.xml.rels", """<Relationships xmlns="$pkg"><Relationship Id="rIdD" Target="../drawings/drawing1.xml"/></Relationships>""")
            put(
                "xl/drawings/drawing1.xml",
                """<xdr:wsDr xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="$rel">""" +
                    """<xdr:twoCellAnchor><xdr:from><xdr:col>9</xdr:col><xdr:row>1</xdr:row></xdr:from><xdr:to><xdr:col>11</xdr:col><xdr:row>3</xdr:row></xdr:to>""" +
                    """<xdr:pic><xdr:blipFill><a:blip r:embed="rIdI"/><a:srcRect l="50000"/></xdr:blipFill></xdr:pic></xdr:twoCellAnchor></xdr:wsDr>""",
            )
            put("xl/drawings/_rels/drawing1.xml.rels", """<Relationships xmlns="$pkg"><Relationship Id="rIdI" Target="../media/image1.png"/></Relationships>""")
            put("xl/media/image1.png", header)
        }
        return out.toByteArray()
    }

    /** K9b: el Excel con el analizador XML y `BitmapFactory` de Android (no los de la JVM): cadenas compartidas, renglones que faltan, la imagen y su recorte; se importa sobre SQLCipher. */
    @Test
    fun elExcelSeLeeYSeImportaEnElEquipo() {
        val png = java.io.ByteArrayOutputStream().also { android.graphics.Bitmap.createBitmap(20, 6, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.GREEN) }.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val bytes = workbook(png)
        val plan = io.github.marioalr.tpvca.domain.imports.ExcelImport.plan(io.github.marioalr.tpvca.domain.imports.Xlsx.read(bytes))
        assertEquals(listOf("Naproxeno 250 mg tabletas", "Crema de ejemplo"), plan.catalog!!.map { it.name })
        val header = plan.header!!
        assertEquals(10, header.fromColumn)
        val cropped = DataController.displayed(header)!!
        val decoded = android.graphics.BitmapFactory.decodeByteArray(cropped, 0, cropped.size)
        assertEquals(10, decoded.width)
        assertEquals(6, decoded.height)
        show()
        compose.runOnIdle { workspace.data.open(ImportFile("Catálogo.xlsx", "") { bytes }) }
        waitFor { workspace.data.ready != null || workspace.data.outcome != null }
        compose.runOnIdle { workspace.data.execute() }
        waitFor { workspace.data.outcome != null }
        assertTrue(workspace.data.outcome!!.message, workspace.data.outcome!!.success)
        assertEquals("NAP-1", store.select("Products", listOf("Code", "Name")).first { it["Name"] == "Naproxeno 250 mg tabletas" }["Code"])
    }

    /** K9b: un servicio con toques sobre SQLCipher, con la sincronización rápida antes de guardarlo. */
    @Test
    fun unServicioSeDaDeAltaConToques() {
        show()
        compose.onNodeWithTag(TabletTags.HUB_CONFIG).performClick()
        waitTag(TabletTags.DASHBOARD)
        compose.onNodeWithTag(TabletTags.configSection("Services")).performScrollTo().performClick()
        waitFor { workspace.services.catalog != null }
        compose.onNodeWithTag(TabletTags.SERVICE_NEW).performClick()
        waitTag(TabletTags.SERVICE_EDITOR)
        compose.onNodeWithTag(TabletTags.field("servicio-nombre")).performTextInput("Toma de muestra de ejemplo")
        compose.onNodeWithTag(TabletTags.field("servicio-precio")).performTextInput("95")
        compose.onNodeWithTag(TabletTags.SERVICE_SAVE).performClick()
        waitFor { store.select("ClinicalServices", listOf("Name")).any { it["Name"] == "Toma de muestra de ejemplo" } }
        waitText("Servicio guardado: Toma de muestra de ejemplo · $95.00")
    }

    /** Revisión de #13: la «mil millones de risas» con el analizador de Android: se rechaza antes de analizar, rápido y sin agotar la memoria. */
    @Test
    fun unLibroConEntidadesSeRechazaEnElEquipo() {
        val lol = "<?xml version=\"1.0\"?><!DOCTYPE lolz [<!ENTITY lol \"lol\">" + (1..9).joinToString("") { i -> "<!ENTITY lol$i \"" + "&lol${if (i == 1) "" else (i - 1).toString()};".repeat(10) + "\">" } + "]><workbook>&lol9;</workbook>"
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("xl/workbook.xml"))
            zip.write(lol.toByteArray())
            zip.closeEntry()
        }
        val started = System.nanoTime()
        val error = runCatching { io.github.marioalr.tpvca.domain.imports.Xlsx.read(out.toByteArray()) }.exceptionOrNull()
        assertTrue(error is io.github.marioalr.tpvca.domain.imports.XlsxException)
        assertTrue("Tardó demasiado", System.nanoTime() - started < 2_000_000_000L)
    }
}
