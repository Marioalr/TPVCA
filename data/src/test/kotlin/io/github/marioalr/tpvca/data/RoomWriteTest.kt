package io.github.marioalr.tpvca.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.marioalr.tpvca.data.security.FileFolioNumbers
import io.github.marioalr.tpvca.sync.Contracts
import io.github.marioalr.tpvca.sync.RealPcControl
import io.github.marioalr.tpvca.sync.ScenarioHarness
import io.github.marioalr.tpvca.sync.engine.OutboxMark
import io.github.marioalr.tpvca.sync.engine.ParkDirection
import io.github.marioalr.tpvca.sync.engine.ParkedRow
import io.github.marioalr.tpvca.sync.engine.RowWrite
import io.github.marioalr.tpvca.sync.engine.SavedChange
import io.github.marioalr.tpvca.sync.engine.StoreChanges
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** La cola de salida sobre Room: lo que `InMemorySyncStore` promete, en la base de verdad. Los escenarios corren aquí con B en Room. */
@RunWith(AndroidJUnit4::class)
class RoomWriteTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: TpvcaDatabase
    private lateinit var store: RoomSyncStore

    @Before
    fun open() {
        database = Room.inMemoryDatabaseBuilder(context, TpvcaDatabase::class.java).allowMainThreadQueries().build()
        store = RoomSyncStore(database, File(context.cacheDir, "membrete-escritura"))
    }

    @After
    fun close() = database.close()

    private fun category(id: String) = RowWrite(
        "Categories", id,
        mapOf("SyncId" to id, "UpdatedAtUtc" to "2026-11-03 10:00:00.0000000", "SyncedAtUtc" to "", "OriginDeviceId" to null, "Description" to null, "Icon" to "", "Name" to "C"),
        isNew = true,
    )

    private fun mark(id: String) = OutboxMark("Category", id, "2026-11-03 10:00:00.0000000")

    private fun seedRow(table: String, syncId: String, values: Map<String, Any?>): RowWrite {
        val full = LinkedHashMap<String, Any?>()
        for (c in io.github.marioalr.tpvca.sync.SyncContract.bundledSchema.tables.first { it.table == table }.columns) {
            full[c.name] = when {
                c.nullable -> null
                c.timeKind != null -> io.github.marioalr.tpvca.sync.ProtocolDate.MIN
                c.remoteType == "INTEGER" -> 0L
                c.remoteType == "REAL" -> 0.0
                c.clrType == "decimal" -> "0"
                else -> ""
            }
        }
        full["SyncId"] = syncId
        full["UpdatedAtUtc"] = "2026-01-01 00:00:00.0000000"
        full["SyncedAtUtc"] = "2026-01-01 00:00:00.000"
        full.putAll(values)
        return RowWrite(table, syncId, full, isNew = true)
    }

    /** Con la base de verdad: si algo falla a media transacción, ni nota, ni venta, ni movimientos, ni cita, y la consulta sigue en curso; con todo bien, queda todo y un segundo intento no cobra otra vez. */
    @Test
    fun terminarYCobrarEsTodoONadaEnLaBaseDeVerdad() {
        val patient = "10000000-0000-4000-8000-000000000001"
        val product = "10000000-0000-4000-8000-000000000002"
        val category = "10000000-0000-4000-8000-000000000003"
        val batch = "10000000-0000-4000-8000-000000000004"
        val appointment = "10000000-0000-4000-8000-000000000005"
        store.apply(
            StoreChanges(
                writes = listOf(
                    seedRow("Patients", patient, mapOf("FullName" to "Ana", "RecordNumber" to "EXP-1", "IsActive" to 1L)),
                    seedRow("Categories", category, mapOf("Name" to "C")),
                    seedRow("Products", product, mapOf("Name" to "Gasa", "Code" to "P1", "Price" to "10.00", "CurrentStock" to 5L, "IsActive" to 1L, "CategorySyncId" to category, "UnitsPerPackage" to 1L)),
                    seedRow("ProductBatches", batch, mapOf("ProductSyncId" to product, "BatchNumber" to "L1", "ExpirationDate" to "2030-01-01 00:00:00.0000000", "Quantity" to 5L)),
                    seedRow("Appointments", appointment, mapOf("PatientRecordSyncId" to patient, "Date" to "2030-01-10 00:00:00.0000000", "Time" to "10:00", "Status" to "Programada", "DurationMinutes" to 30L)),
                ),
                meta = mapOf(
                    io.github.marioalr.tpvca.sync.engine.SyncEngine.DEVICE_ROLE to "AndroidTablet",
                    io.github.marioalr.tpvca.sync.engine.SyncEngine.DEVICE_CODE to "T1",
                    io.github.marioalr.tpvca.sync.engine.SyncEngine.FIRST_DOWNLOAD_DONE to "2026-01-01 00:00:00.0000000",
                ),
            ),
        )
        val writes = io.github.marioalr.tpvca.sync.engine.WriteEngine(store, { "tablet" }, io.github.marioalr.tpvca.sync.link.MemoryFolioNumbers(), { null })
        val draft = io.github.marioalr.tpvca.sync.rules.consultation.ConsultationDraft(
            patientSyncId = patient, patientName = "Ana", folio = "CNS-20261106-103000-T1", startedAt = "2026-11-06T10:30:00", attendingAppointmentSyncId = appointment, chargeSaleSyncId = io.github.marioalr.tpvca.sync.rules.SyncIds.random(),
        )
        assertEquals(null, writes.startConsultation(draft))
        fun request(productId: String) = io.github.marioalr.tpvca.sync.engine.FinishConsultation(
            patientSyncId = patient,
            note = io.github.marioalr.tpvca.sync.engine.ConsultationNote(draft.folio, java.time.LocalDateTime.of(2026, 11, 6, 10, 30), "Fiebre", "", "", "", "Faringitis", "Reposo", "", "[]", false, ""),
            lines = listOf(io.github.marioalr.tpvca.sync.engine.SaleLine(productSyncId = productId, quantity = 2, unitPrice = java.math.BigDecimal("10.00"), code = "P1", description = "Gasa")),
            saleSyncId = draft.chargeSaleSyncId,
            patientName = "Ana",
            paymentMethod = "Efectivo",
            responsible = "Dra. Ejemplo",
            appointmentSyncId = appointment,
            restorePrescriptionJson = null,
        )

        // El expediente se dio de baja: falla después de escribir la nota, dentro de la transacción
        store.apply(StoreChanges(writes = listOf(seedRow("Patients", patient, mapOf("FullName" to "Ana", "RecordNumber" to "EXP-1", "IsActive" to 0L)).copy(isNew = false))))
        assertThrows(io.github.marioalr.tpvca.sync.engine.WriteRefused::class.java) { writes.finishConsultation(request(product)) }
        assertTrue(store.select("PatientConsultations", listOf("SyncId")).isEmpty())
        assertTrue(store.select("Sales", listOf("SyncId")).isEmpty())
        assertTrue(store.select("InventoryMovements", listOf("SyncId")).isEmpty())
        assertEquals(5L, store.select("ProductBatches", listOf("Quantity")).single()["Quantity"])
        assertEquals("Programada", store.select("Appointments", listOf("Status")).single()["Status"])
        assertEquals(0, store.pendingChanges())
        assertTrue("La consulta sigue en curso", store.meta(io.github.marioalr.tpvca.sync.engine.WriteEngine.CONSULTATION_DRAFT) != null)

        store.apply(StoreChanges(writes = listOf(seedRow("Patients", patient, mapOf("FullName" to "Ana", "RecordNumber" to "EXP-1", "IsActive" to 1L)).copy(isNew = false))))
        val done = writes.finishConsultation(request(product))
        assertTrue(done.sale!!.folio.contains("-T1-"))
        assertEquals(1, store.select("PatientConsultations", listOf("SyncId")).size)
        assertEquals(3L, store.select("ProductBatches", listOf("Quantity")).single()["Quantity"])
        assertEquals("Atendida", store.select("Appointments", listOf("Status")).single()["Status"])
        assertEquals(null, store.meta(io.github.marioalr.tpvca.sync.engine.WriteEngine.CONSULTATION_DRAFT))
        assertTrue(store.pendingChanges() > 0)

        // Otra vez, como después de morir el proceso: no cobra de nuevo
        val again = writes.finishConsultation(request(product))
        assertTrue(again.alreadyFinished)
        assertEquals(done.sale!!.folio, again.sale?.folio)
        assertEquals(1, store.select("Sales", listOf("SyncId")).size)
    }

    @Test
    fun laEscrituraYSuEntradaVanEnLaMismaTransaccion() {
        assertThrows(IllegalStateException::class.java) {
            store.transaction {
                store.apply(StoreChanges(writes = listOf(category("a")), outbox = listOf(mark("a"))))
                error("Se cayó a media operación")
            }
        }
        assertEquals(0, store.pendingChanges())
        assertTrue(store.existing("Categories", listOf("a")).isEmpty())

        store.apply(StoreChanges(writes = listOf(category("a")), outbox = listOf(mark("a"))))
        assertEquals(1, store.pendingChanges())
        assertEquals(setOf("a"), store.existing("Categories", listOf("a")))
    }

    @Test
    fun anidadaEsUnPuntoDeGuardado() {
        store.transaction {
            store.apply(StoreChanges(writes = listOf(category("afuera"))))
            assertThrows(IllegalStateException::class.java) {
                store.transaction {
                    store.apply(StoreChanges(writes = listOf(category("adentro"))))
                    error("Esta no se deja")
                }
            }
            assertTrue(store.existing("Categories", listOf("adentro")).isEmpty())
        }
        assertEquals(setOf("afuera"), store.existing("Categories", listOf("afuera", "adentro")))
    }

    @Test
    fun cadaAnotacionTomaUnNumeroMayorYLoEditadoDuranteLaSubidaSeQueda() {
        store.apply(StoreChanges(outbox = listOf(mark("a"), mark("b"))))
        val first = store.outbox()
        assertEquals(listOf("a", "b"), first.map { it.syncId })
        val uploaded = first.maxOf { it.seq }
        store.apply(StoreChanges(outbox = listOf(mark("a"))))
        assertEquals("Una entrada por registro", 2, store.pendingChanges())
        assertTrue(store.outbox().single { it.syncId == "a" }.seq > uploaded)
        store.apply(StoreChanges(outboxClearedUpTo = uploaded))
        assertEquals("a", store.outbox().single().syncId)
        // Vacía la cola: la secuencia sigue subiendo, no vuelve a empezar
        store.apply(StoreChanges(outboxClearedUpTo = Long.MAX_VALUE))
        store.apply(StoreChanges(outbox = listOf(mark("c"))))
        assertTrue(store.outbox().single().seq > uploaded)
    }

    @Test
    fun loApartadoAlSubirCuentaUnaSolaVez() {
        store.apply(StoreChanges(outbox = listOf(mark("a")), park = listOf(ParkedRow(ParkDirection.PUSH, "Category", "a", "", "falta"), ParkedRow(ParkDirection.PUSH, "Category", "b", "", "falta"))))
        assertEquals(2, store.pendingChanges())
        assertEquals("2026-11-03 10:00:00.0000000", store.oldestPendingUtc())
    }

    @Test
    fun vaciarNoSePuedeConAlgoEnLaColaYLoGuardadoSeQueda() {
        store.apply(StoreChanges(writes = listOf(category("a")), outbox = listOf(mark("a"))))
        assertThrows(IllegalStateException::class.java) { store.clearSyncedData(emptyList()) }
        assertEquals("No borró nada", setOf("a"), store.existing("Categories", listOf("a")))

        val saved = SavedChange(0, SavedChange.SOURCE_THIS_DEVICE, "g", "Category", "a", SavedChange.KIND_ROW, "{}", 3, 21, "t")
        store.apply(StoreChanges(saved = listOf(saved), outboxClearedUpTo = Long.MAX_VALUE))
        store.clearSyncedData(emptyList())
        assertTrue(store.existing("Categories", listOf("a")).isEmpty())
        assertEquals("Reemplaza el mismo registro", "a", store.savedChanges().single().syncId)
        store.apply(StoreChanges(savedRemoved = listOf(store.savedChanges().single().id)))
        assertTrue(store.savedChanges().isEmpty())
    }

    @Test
    fun reparaLasMarcasEnElFuturoTambienLasDeLasLapidas() {
        store.apply(StoreChanges(writes = listOf(category("a")), tombstones = listOf(io.github.marioalr.tpvca.sync.engine.TombstoneRow("Category", "b", "2030-01-01 00:00:00.0000000", "", null))))
        assertEquals(1, store.repairFutureStamps(listOf("Categories"), "2029-01-01 00:00:00.0000000", "2028-01-01 00:00:00.0000000"))
    }

    @Test
    fun migrarDeLaVersionUnoConservaLoQueBajoYAgregaLaCola() {
        val name = "migracion-${System.nanoTime()}.db"
        // La versión 1 tal como se publicó: lo mismo sin las dos tablas nuevas
        val first = Room.databaseBuilder(context, TpvcaDatabase::class.java, name).allowMainThreadQueries().build()
        RoomSyncStore(first, File(context.cacheDir, "m")).apply(StoreChanges(writes = listOf(category("a")), meta = mapOf("Cloud:ClinicId" to "c")))
        first.openHelper.writableDatabase.apply {
            execSQL("DROP TABLE SyncOutbox")
            execSQL("DROP TABLE SyncSavedChanges")
            version = 1
        }
        first.close()

        val migrated = Room.databaseBuilder(context, TpvcaDatabase::class.java, name).addMigrations(TpvcaDatabase.MIGRATION_1_2).allowMainThreadQueries().build()
        val store = RoomSyncStore(migrated, File(context.cacheDir, "m"))
        assertEquals("Lo que bajó sigue ahí", setOf("a"), store.existing("Categories", listOf("a")))
        assertEquals("c", store.meta("Cloud:ClinicId"))
        store.apply(StoreChanges(outbox = listOf(mark("a"))))
        assertEquals(1, store.pendingChanges())
        migrated.close()
        context.deleteDatabase(name)
    }

    @Test
    fun elArchivoExportadoEsElDeLaPc() {
        val file = File(context.cacheDir, "exportado.db")
        val change = SavedChange(0, SavedChange.SOURCE_THIS_DEVICE, "g", "PatientRecord", "a", SavedChange.KIND_ROW, "{\"SyncId\":{\"type\":\"text\",\"value\":\"a\"}}", 3, 21, "t")
        SavedChangesFile.write(file, listOf(change))
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT Source, Generation, EntityType, SyncId, Kind, Payload, Protocol, Contract, SavedAtUtc FROM SyncSavedChanges", null).use {
                assertTrue(it.moveToFirst())
                assertEquals(listOf("este-equipo", "g", "PatientRecord", "a", "row", change.payload, "3", "21", "t"), (0..8).map(it::getString))
            }
        }
    }

    @Test
    fun elUltimoFolioSobreviveFueraDeLaBase() {
        val file = File(context.cacheDir, "folios-prueba.json").also { it.delete() }
        val numbers = FileFolioNumbers(file)
        assertEquals(0, numbers.last("EXP-2026-T1"))
        assertEquals(1, numbers.reserve("EXP-2026-T1", 0))
        assertEquals("El mayor local manda si es mayor", 4, numbers.reserve("EXP-2026-T1", 3))
        assertEquals("Y nunca baja", 5, numbers.reserve("EXP-2026-T1", 0))
        assertEquals("Una instancia nueva lee lo mismo", 5, FileFolioNumbers(file).last("EXP-2026-T1"))
        // Dos hilos a la vez no pueden tomar el mismo
        val taken = (1..20).map { java.util.concurrent.CompletableFuture.supplyAsync { numbers.reserve("TKT-T1", 0) } }.map { it.get() }
        assertEquals(20, taken.toSet().size)
        file.writeText("no es json")
        assertEquals(0, numbers.last("EXP-2026-T1"))
    }

    private fun scenario(name: String) {
        val differences = ScenarioHarness(Contracts.file("scenarios/$name.json"), System.getProperty("tpvc.control")?.takeIf { it.isNotEmpty() }?.let(::RealPcControl), storeFor = { store }).run()
        assertEquals(name, emptyList<String>(), differences)
    }

    @Test fun lwwAndTies() = scenario("lww-and-ties")

    @Test fun tombstones() = scenario("tombstones")

    @Test fun parkedRows() = scenario("parked-rows")

    @Test fun clockSkew() = scenario("clock-skew")

    @Test fun uploadCut() = scenario("upload-cut")

    @Test fun generationFence() = scenario("generation-fence")

    @Test fun folios() = scenario("folios")

    @Test fun stockLedger() = scenario("stock-ledger")

    @Test fun patientMerge() = scenario("patient-merge")
}
