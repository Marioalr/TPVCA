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
