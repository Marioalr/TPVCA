package io.github.marioalr.tpvca.dev

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import io.github.marioalr.tpvca.data.RoomSyncStore
import io.github.marioalr.tpvca.data.SqlCipher
import io.github.marioalr.tpvca.data.TpvcaDatabase
import io.github.marioalr.tpvca.data.clinic.ClinicReader
import io.github.marioalr.tpvca.documents.SharedDocuments
import io.github.marioalr.tpvca.domain.Clinic
import io.github.marioalr.tpvca.domain.devices.DeviceRole
import io.github.marioalr.tpvca.sync.ProtocolDate
import io.github.marioalr.tpvca.sync.engine.LocalSide
import io.github.marioalr.tpvca.sync.engine.SavedChange
import io.github.marioalr.tpvca.sync.engine.StoreChanges
import io.github.marioalr.tpvca.sync.engine.SyncEngine
import io.github.marioalr.tpvca.sync.engine.SyncEngineConfig
import io.github.marioalr.tpvca.sync.engine.SyncNotice
import io.github.marioalr.tpvca.sync.engine.SyncNotices
import io.github.marioalr.tpvca.sync.engine.SyncStore
import io.github.marioalr.tpvca.sync.engine.WriteEngine
import io.github.marioalr.tpvca.sync.hrana.HranaConnection
import io.github.marioalr.tpvca.sync.hrana.HranaException
import io.github.marioalr.tpvca.sync.hrana.ResultSet
import io.github.marioalr.tpvca.sync.hrana.Statement
import io.github.marioalr.tpvca.sync.link.MemoryFolioNumbers
import io.github.marioalr.tpvca.ui.phone.PhoneEnv
import io.github.marioalr.tpvca.ui.phone.PhoneStatus
import io.github.marioalr.tpvca.ui.tablet.TabletApp
import io.github.marioalr.tpvca.ui.tablet.TabletChrome
import io.github.marioalr.tpvca.ui.tablet.TabletEnv
import io.github.marioalr.tpvca.ui.tablet.TabletWorkspace
import io.github.marioalr.tpvca.ui.theme.TpvcTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant

/** Una nube que nunca responde: la demostración trabaja sin conexión, como la tablet en el consultorio sin internet. */
object NoCloud : HranaConnection {
    override fun query(statements: List<Statement>): List<ResultSet> = throw HranaException(null, "sin nube", null)

    override fun snapshot(before: List<Statement>, statements: List<Statement>): io.github.marioalr.tpvca.sync.hrana.Snapshot = throw HranaException(null, "sin nube", null)
}

/** Lo que `PhoneDemoSeed` no trae y solo tiene la tablet: el rol, un folio regularizado, avisos que llegaron y un cambio guardado. Todo inventado. */
fun tabletDemoExtras(store: SyncStore) {
    val row = store.select("Patients", listOf("SyncId", "RecordNumber"))
    val ana = row.first { it["RecordNumber"] == "EXP-2026-014" }["SyncId"] as String
    val juan = row.first { it["RecordNumber"] == "EXP-2026-009" }["SyncId"] as String
    val products = store.select("Products", listOf("SyncId", "Code"))
    fun product(code: String) = products.first { it["Code"] == code }["SyncId"] as String
    PhoneDemoSeed(store, Clinic.today(Instant.now())).fillConsultationCatalog(product("INS-001"), product("INS-002"), juan, "Paciente Juan Ejemplo Gómez")
    val appointments = store.select("Appointments", listOf("SyncId", "Time")).filter { it["Time"] == "10:30" || it["Time"] == "10:45" }.map { it["SyncId"] as String }.sorted()
    store.apply(
        StoreChanges(
            writes = listOf(
                io.github.marioalr.tpvca.sync.engine.RowWrite("Patients", ana, mapOf("OriginalFolio" to "EXP-2026-T1-003", "BloodType" to "O+", "ClinicalNotes" to "Nota de prueba", "ActivePrescriptionJson" to """[{"RowId":"10000000-0000-4000-8000-00000000d001","MedicineName":"Tempra 500 mg tabletas (Paracetamol)","Dosage":"1 tableta cada 8 horas por 3 días","RecommendedQuantity":1,"Presentation":"Caja con 20","ActiveIngredient":"Paracetamol","ShowActiveIngredient":true}]"""), isNew = false),
            ),
            meta = buildMap {
                put(SyncEngine.DEVICE_ROLE, DeviceRole.AndroidTablet.name)
                put(SyncEngine.DEVICE_CODE, "T1")
                put(SyncEngine.FIRST_DOWNLOAD_DONE, ProtocolDate.format(java.time.LocalDateTime.of(2026, 1, 1, 0, 0)))
                put(SyncEngine.NOTICES, SyncNotices.write(if (appointments.size == 2) setOf(SyncNotice(SyncNotices.OVERLAP, appointments[0], appointments[1])) else emptySet()))
            },
            saved = listOf(SavedChange(source = SavedChange.SOURCE_THIS_DEVICE, generation = "demo", entityType = "PatientRecord", syncId = juan, kind = SavedChange.KIND_TOMBSTONE, payload = "2026-01-01T00:00:00.0000000", protocol = 3, contract = 22, savedAtUtc = "2026-01-01T00:00:00.0000000")),
        ),
    )
}

/**
 * El celular de la demostración (K4b): su rol aprobado y la descarga hecha, para que agende sin nube. Con [notice], también el aviso de los dos empalmes de hoy como si hubieran llegado de otro equipo. Todo inventado.
 */
fun phoneDemoExtras(store: SyncStore, notice: Boolean) {
    val appointments = store.select("Appointments", listOf("SyncId", "Time")).filter { it["Time"] == "10:30" || it["Time"] == "10:45" }.map { it["SyncId"] as String }.sorted()
    store.apply(
        StoreChanges(
            meta = buildMap {
                put(SyncEngine.DEVICE_ROLE, DeviceRole.AndroidMobileAdmin.name)
                put(SyncEngine.DEVICE_CODE, "C1")
                put(SyncEngine.FIRST_DOWNLOAD_DONE, ProtocolDate.format(java.time.LocalDateTime.of(2026, 1, 1, 0, 0)))
                if (notice && appointments.size == 2) put(SyncEngine.NOTICES, SyncNotices.write(setOf(SyncNotice(SyncNotices.OVERLAP, appointments[0], appointments[1]))))
            },
        ),
    )
}

/** Solo en la demostración: una jeringa dada de alta otra vez en «otro equipo» con el mismo código, y su aviso arriba de Productos (K9b). Inventado. */
fun catalogDuplicateDemo(store: SyncStore) {
    val products = store.select("Products", listOf("SyncId", "Code", "CategorySyncId"))
    val jeringa = products.first { it["Code"] == "INS-002" }
    val twin = "10000000-0000-4000-8000-00000000c0de"
    io.github.marioalr.tpvca.sync.engine.LocalWriter(store, { "otro-equipo" }).create(
        "Products", twin,
        mapOf("Code" to "INS-002", "Name" to "Jeringa 5 ml", "Presentation" to "Pieza estéril", "CategorySyncId" to jeringa["CategorySyncId"], "Price" to "12.00", "IsActive" to true, "CurrentStock" to 10L, "MinStock" to 10L),
    )
    store.apply(StoreChanges(outboxClearedUpTo = Long.MAX_VALUE))
    val ids = listOf(jeringa["SyncId"] as String, twin).sorted()
    val notices = SyncNotices.read(store.meta(SyncEngine.NOTICES)) + SyncNotice(SyncNotices.catalogKind(io.github.marioalr.tpvca.domain.business.CatalogDuplicateRules.Kind.Product), ids[0], ids[1])
    store.apply(StoreChanges(meta = mapOf(SyncEngine.NOTICES to SyncNotices.write(notices))))
}

/** La tablet con datos inventados y sin nube, sobre SQLCipher. Solo en depuración. Girar no la vuelve a armar; que Android cierre el proceso sí. `--es pagina hub|pacientes|ficha|alta|editar|agenda|sync|consulta|enviada|venta|productos|lotes|kardex|sugerida|panel|negocio|servicios|consultorio|datos`. */
class TabletDemoActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)
        val demo = holder(this)
        if (savedInstanceState == null) when (intent.getStringExtra("pagina")) {
            "pacientes" -> demo.workspace.goPatients()
            "ficha" -> demo.workspace.openChart(demo.ana())
            "alta" -> demo.workspace.newPatient()
            "editar" -> demo.workspace.editPatient(demo.ana())
            "agenda" -> {
                demo.workspace.goPatients()
                demo.workspace.openAgenda()
            }
            "sync" -> demo.workspace.goSync()
            "consulta" -> demo.workspace.consultation.chartPrimaryAction(demo.ana())
            // La consulta de Ana enviada a la PC (K8): para ver la solo lectura sin otro equipo
            "enviada" -> demo.sendAnaToPc()
            "venta" -> demo.workspace.enterPointOfSale()
            // Configuración (K9b)
            "productos" -> demo.workspace.enterConfig(io.github.marioalr.tpvca.ui.tablet.ConfigSection.Inventory)
            "lotes" -> demo.workspace.enterConfig(io.github.marioalr.tpvca.ui.tablet.ConfigSection.Batches)
            "kardex" -> demo.workspace.enterConfig(io.github.marioalr.tpvca.ui.tablet.ConfigSection.Movements)
            "sugerida" -> demo.workspace.inventory.openSuggested()
            // La segunda mitad de K9b
            "panel" -> demo.workspace.enterConfig(io.github.marioalr.tpvca.ui.tablet.ConfigSection.Dashboard)
            "negocio" -> demo.workspace.enterConfig(io.github.marioalr.tpvca.ui.tablet.ConfigSection.Business)
            "servicios" -> demo.workspace.enterConfig(io.github.marioalr.tpvca.ui.tablet.ConfigSection.Services)
            "consultorio" -> demo.workspace.enterConfig(io.github.marioalr.tpvca.ui.tablet.ConfigSection.Clinic)
            "datos" -> demo.workspace.enterConfig(io.github.marioalr.tpvca.ui.tablet.ConfigSection.Data)
        }
        setContent {
            TpvcTheme {
                TabletApp(demo.workspace, TabletChrome(PhoneStatus(null, false, Instant.now().minusSeconds(120), false, "Consultorio de Prueba"), 0L, "0.1.0", {}, {}))
            }
        }
    }

    private class Demo(context: Context) {
        val store: RoomSyncStore
        val workspace: TabletWorkspace

        init {
            val name = "demo-tablet.db"
            val fresh = !context.getDatabasePath(name).exists()
            val database = TpvcaDatabase.open(context, name, SqlCipher.factory(ByteArray(32)))
            val assets = File(context.cacheDir, "demo-membrete-tablet")
            store = RoomSyncStore(database, assets)
            if (fresh) {
                PhoneDemoSeed(store, Clinic.today(Instant.now())).fill()
                tabletDemoExtras(store)
                catalogDuplicateDemo(store)
            }
            val folios = MemoryFolioNumbers()
            val env = TabletEnv(
                PhoneEnv(ClinicReader({ database.clinic() }, { assets }), SharedDocuments(context), labFiles = null),
                writes = { WriteEngine(store, { "demo-tablet" }, folios, { null }) },
                local = { LocalSide(store) },
                reapplySaved = { SyncEngine(NoCloud, store, SyncEngineConfig("demo-tablet")).reapplySaved() },
                quickSync = { true },
                noteLocalWrite = {},
                refreshNow = {},
                isOnline = { false },
            )
            workspace = TabletWorkspace(env, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
        }

        fun sendAnaToPc() {
            val ana = ana()
            workspace.consultation.chartPrimaryAction(ana)
            CoroutineScope(Dispatchers.Main.immediate).launch {
                while (workspace.consultation.draft == null) kotlinx.coroutines.delay(50)
                val d = workspace.consultation.draft!!
                workspace.consultation.markHandoff(
                    io.github.marioalr.tpvca.sync.link.HandoffMarker(
                        id = "demo-envio", toDeviceId = "demo-pc", toDeviceName = "PC del consultorio", fromDeviceId = "demo-tablet", fromDeviceName = "Tablet",
                        databaseUrl = "", patientSyncId = ana, patientName = d.patientName, folio = d.folio, phase = io.github.marioalr.tpvca.sync.link.HandoffPhase.Sent,
                    ),
                )
            }
        }

        fun ana(): String = store.select("Patients", listOf("SyncId", "RecordNumber")).first { it["RecordNumber"] == "EXP-2026-014" }["SyncId"] as String
    }

    private companion object {
        private var demo: Demo? = null

        fun holder(context: Context): Demo = demo ?: Demo(context.applicationContext).also { demo = it }
    }
}
