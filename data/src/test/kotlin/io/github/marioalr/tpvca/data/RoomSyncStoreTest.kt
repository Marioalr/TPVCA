package io.github.marioalr.tpvca.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.marioalr.tpvca.sync.MutableClock
import io.github.marioalr.tpvca.sync.RecordingReplay
import io.github.marioalr.tpvca.sync.Transcript
import io.github.marioalr.tpvca.sync.engine.Nullify
import io.github.marioalr.tpvca.sync.engine.ParkDirection
import io.github.marioalr.tpvca.sync.engine.ParkedRow
import io.github.marioalr.tpvca.sync.engine.RowWrite
import io.github.marioalr.tpvca.sync.engine.StoreChanges
import io.github.marioalr.tpvca.sync.engine.SyncEngine
import io.github.marioalr.tpvca.sync.engine.SyncEngineConfig
import io.github.marioalr.tpvca.sync.engine.SyncOutcome
import io.github.marioalr.tpvca.sync.engine.SyncTrigger
import io.github.marioalr.tpvca.sync.engine.TombstoneRow
import io.github.marioalr.tpvca.sync.hrana.HranaClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

/** El motor sobre la base real de Room, con la misma grabación que corre contra el doble en memoria. */
@RunWith(AndroidJUnit4::class)
class RoomSyncStoreTest {
    private lateinit var database: TpvcaDatabase
    private lateinit var store: RoomSyncStore
    private lateinit var assets: File

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, TpvcaDatabase::class.java).allowMainThreadQueries().build()
        assets = File(context.cacheDir, "membrete-prueba").also { it.deleteRecursively() }
        store = RoomSyncStore(database, assets)
    }

    @After
    fun close() = database.close()

    private fun category(id: String, stamp: String = "2026-11-03 10:00:00.0000000", name: String = "C") = RowWrite(
        "Categories", id,
        mapOf("SyncId" to id, "UpdatedAtUtc" to stamp, "SyncedAtUtc" to "2026-11-03 10:00:00.000", "OriginDeviceId" to null, "Description" to null, "Icon" to "", "Name" to name),
        isNew = true,
    )

    @Test
    fun laPrimeraDescargaGrabadaQuedaEnLaBase() {
        RecordingReplay(
            Transcript.load("first-download"),
            skipped = mapOf(
                1 to RecordingReplay.PC_SCHEMA_PROBE, 2 to RecordingReplay.PC_JOIN_IDENTITY,
                3 to RecordingReplay.HEARTBEAT_IN_K3, 20 to RecordingReplay.PC_REGISTRATION,
            ),
        ).use { replay ->
            val engine = SyncEngine(
                HranaClient(replay.url, RecordingReplay.TOKEN, "0.1.0"),
                store,
                SyncEngineConfig("device-tablet"),
                clock = MutableClock(Instant.parse("2026-11-03T18:00:00Z")),
            )
            val outcome = engine.sync(SyncTrigger.USER)
            assertTrue("$outcome", outcome is SyncOutcome.Completed)
            assertTrue(replay.failures.joinToString("\n"), replay.failures.isEmpty())
            assertEquals(emptyList<Int>(), replay.unconsumed)

            val counts = store.rowCounts()
            for (table in listOf("Categories", "Patients", "Appointments", "PatientConsultations", "Products")) assertEquals(table, 1L, counts[table])
            val patient = store.select("Patients", listOf("FullName", "BirthDate", "IsActive", "SyncedAtUtc"), "SyncId", listOf("10000000-0000-4000-8000-000000000003")).single()
            assertEquals("Ana Pérez Núñez", patient["FullName"])
            assertEquals("1985-03-02 00:00:00.0000000", patient["BirthDate"])
            assertEquals(1L, patient["IsActive"])
            assertEquals(Transcript.SERVED_TIME, patient["SyncedAtUtc"])
            assertEquals("${Transcript.SERVED_TIME}|", store.meta(SyncEngine.CURSOR_PREFIX + "Patients"))
        }
    }

    @Test
    fun laActualizacionSoloTocaLasColumnasQueLlegan() {
        store.apply(StoreChanges(writes = listOf(category("c1", name = "Antes"))))
        store.apply(StoreChanges(writes = listOf(RowWrite("Categories", "c1", mapOf("Name" to "Después"), isNew = false))))
        val row = store.select("Categories", listOf("Name", "Icon", "UpdatedAtUtc")).single()
        assertEquals("Después", row["Name"])
        assertEquals("", row["Icon"])
        assertEquals("2026-11-03 10:00:00.0000000", row["UpdatedAtUtc"])
    }

    @Test
    fun marcasExistentesYReferencias() {
        store.apply(StoreChanges(writes = listOf(category("c1"), category("c2"))))
        assertEquals(setOf("c1"), store.existing("Categories", listOf("c1", "falta")))
        assertEquals(mapOf("c2" to "2026-11-03 10:00:00.0000000"), store.stamps("Categories", listOf("c2")))
        // Más variables de las que acepta una instrucción en Android 8
        assertEquals(2, store.stamps("Categories", (1..1500).map { "x$it" } + listOf("c1", "c2")).size)
    }

    @Test
    fun todoSeAplicaEnUnaTransaccion() {
        val bad = StoreChanges(writes = listOf(category("c1")), meta = mapOf("Clave" to "valor"), nullify = listOf(Nullify("NoExiste", "X", setOf("a"))))
        assertTrue(runCatching { store.apply(bad) }.isFailure)
        assertEquals(0L, store.rowCounts()["Categories"])
        assertNull(store.meta("Clave"))
    }

    @Test
    fun lasLapidasSeQuedanConLaMasNueva() {
        val old = TombstoneRow("Category", "c1", "2026-11-03 10:00:00.0000000", "2026-11-03 10:00:00.000", "pc")
        val new = old.copy(deletedAtUtc = "2026-11-03 11:00:00.0000000")
        store.apply(StoreChanges(tombstones = listOf(new)))
        store.apply(StoreChanges(tombstones = listOf(old)))
        assertEquals(setOf("c1"), store.tombstoned("Category", listOf("c1", "c2")))
        assertEquals(new.deletedAtUtc, store.select("SyncTombstones", listOf("DeletedAtUtc")).single()["DeletedAtUtc"])
    }

    @Test
    fun lasApartadasIdaYVuelta() {
        val parked = ParkedRow(ParkDirection.PULL, "LabResult", "l1", "{}", "falta")
        store.apply(StoreChanges(park = listOf(parked, parked.copy(reason = "otra"))))
        assertEquals("otra", store.parkedRows().single().reason)
        store.apply(StoreChanges(unpark = listOf(Triple(ParkDirection.PULL, "LabResult", "l1"))))
        assertTrue(store.parkedRows().isEmpty())
    }

    @Test
    fun marcasEnElFuturo() {
        store.apply(StoreChanges(writes = listOf(category("c1", stamp = "2026-11-03 21:00:00.0000000"), category("c2"))))
        store.repairFutureStamps(listOf("Categories"), "2026-11-03 18:02:00.0000000", "2026-11-03 18:00:00.0000000")
        assertEquals("2026-11-03 18:00:00.0000000", store.stamps("Categories", listOf("c1"))["c1"])
        assertEquals("2026-11-03 10:00:00.0000000", store.stamps("Categories", listOf("c2"))["c2"])
    }

    @Test
    fun vaciarConservaLoQueSeLePide() {
        store.apply(StoreChanges(writes = listOf(category("c1")), meta = mapOf("Usage:Day" to "hoy", "PullCursor:Categories" to "x|y")))
        store.clearSyncedData(listOf("Usage:"))
        assertEquals(0L, store.rowCounts()["Categories"])
        assertEquals("hoy", store.meta("Usage:Day"))
        assertNull(store.meta("PullCursor:Categories"))
    }

    @Test
    fun elPerfilYElMembrete() {
        store.saveProfile(mapOf("Id" to 1L, "DoctorName" to "Dra.", "UpdatedAtUtc" to "2026-11-03 10:00:00.0000000", "ColumnaFutura" to "se ignora"))
        assertEquals("2026-11-03 10:00:00.0000000", store.profileStamp())
        assertFalse(store.hasAsset("abc"))
        store.saveAsset("abc", "crest", byteArrayOf(1, 2, 3))
        assertTrue(store.hasAsset("abc"))
        assertArrayEquals(byteArrayOf(1, 2, 3), File(assets, "abc").readBytes())
    }

    @Test
    fun borrarTodo() {
        store.apply(StoreChanges(writes = listOf(category("c1")), meta = mapOf("Usage:Day" to "hoy")))
        store.saveAsset("abc", "crest", byteArrayOf(1))
        store.wipeEverything()
        assertEquals(0L, store.rowCounts()["Categories"])
        assertNull(store.meta("Usage:Day"))
        assertFalse(store.hasAsset("abc"))
    }
}
