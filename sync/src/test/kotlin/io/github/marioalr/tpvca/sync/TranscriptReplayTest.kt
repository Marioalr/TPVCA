package io.github.marioalr.tpvca.sync

import io.github.marioalr.tpvca.sync.RecordingReplay.Companion.HEARTBEAT_IN_K3
import io.github.marioalr.tpvca.sync.RecordingReplay.Companion.PC_JOIN_IDENTITY
import io.github.marioalr.tpvca.sync.RecordingReplay.Companion.PC_REGISTRATION
import io.github.marioalr.tpvca.sync.RecordingReplay.Companion.PC_SCHEMA_PROBE
import io.github.marioalr.tpvca.sync.engine.SyncEngine
import io.github.marioalr.tpvca.sync.engine.SyncEngineConfig
import io.github.marioalr.tpvca.sync.engine.SyncOutcome
import io.github.marioalr.tpvca.sync.engine.SyncStore
import io.github.marioalr.tpvca.sync.engine.SyncTrigger
import io.github.marioalr.tpvca.sync.hrana.HranaClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * Conformidad con las grabaciones de la PC (§7.6): cada petición de Kotlin coincide con la grabada y se responde con lo grabado. Lo que
 * le toca solo a la PC se salta por número y con su motivo ([RecordingReplay]).
 */
class TranscriptReplayTest {
    private val clock = MutableClock(Instant.parse("2026-11-03T18:00:00Z"))

    private fun engine(replay: RecordingReplay, store: SyncStore, deviceId: String = "device-tablet", pageSize: Int? = null) = SyncEngine(
        HranaClient(replay.url, RecordingReplay.TOKEN, "0.1.0"),
        store,
        SyncEngineConfig(deviceId, pageSizeOverride = pageSize),
        clock = clock,
    )

    private fun assertClean(replay: RecordingReplay) {
        assertTrue(replay.failures.joinToString("\n"), replay.failures.isEmpty())
        assertEquals("Intercambios sin pedir de ${replay.transcript.scenario}", emptyList<Int>(), replay.unconsumed)
        assertFalse("Nunca DDL", replay.sqlSeen.any { it.contains("CREATE ", ignoreCase = true) || it.contains("ALTER ", ignoreCase = true) })
        val literal = Contracts.dir.resolve("hrana").listFiles { f -> f.extension == "jsonl" }!!
            .flatMap { Transcript.load(it.nameWithoutExtension).exchanges.flatMap { e -> e.statements.map { s -> s.sql } } }.toSet()
        val contract = Json.parseToJsonElement(Contracts.file("hrana/statements.json").readText()).jsonObject.getValue("statements").jsonArray
            .map { it.jsonObject.getValue("sql").jsonPrimitive.content }.toSet()
        assertEquals("Solo el SQL del contrato", emptySet<String>(), replay.sqlSeen - literal - contract)
    }

    /** Una base que ya bajó todo antes, con la identidad de la grabación. */
    private fun syncedStore(): InMemorySyncStore = InMemorySyncStore().apply {
        metaValues[SyncEngine.FIRST_DOWNLOAD_DONE] = "2026-11-02T18:00:00Z"
        metaValues[SyncEngine.MANIFEST_CONTRACT] = SyncContract.REVISION.toString()
            metaValues[SyncEngine.CLOUD_SCHEMA_SEEN] = SyncContract.bundledSchema.fingerprint
        metaValues[SyncEngine.CLINIC_ID] = "<id:1>"
        metaValues[SyncEngine.GENERATION] = "<id:2>"
        metaValues[SyncEngine.SUMMARY_DAY] = "2026-11-03"
        for (table in SyncContract.bundledSchema.tables.map { it.table } + "SyncTombstones") {
            metaValues[SyncEngine.CURSOR_PREFIX + table] = "2026-11-03 17:59:00.000|"
        }
    }

    @Test
    fun primeraDescarga() {
        RecordingReplay(
            Transcript.load("first-download"),
            skipped = mapOf(1 to PC_SCHEMA_PROBE, 2 to PC_JOIN_IDENTITY, 3 to HEARTBEAT_IN_K3, 20 to PC_REGISTRATION),
        ).use { replay ->
            val store = InMemorySyncStore()
            val outcome = engine(replay, store).sync(SyncTrigger.USER)

            assertTrue("$outcome", outcome is SyncOutcome.Completed && outcome.fullDownload && outcome.readOnly == null)
            assertClean(replay)
            // Las lecturas de Kotlin que van antes de la marca de agua (§7.3): claves, luego su fila
            assertEquals(listOf(Statements.META_ALL, Statements.REGISTRY_OWN), replay.extrasServed)
            assertEquals((4..19).toList(), replay.consumed)

            val counts = store.rowCounts()
            for (table in listOf("Categories", "Patients", "Appointments", "PatientConsultations", "Products")) assertEquals(table, 1L, counts[table])
            assertEquals(0L, counts["Sales"])

            val patient = store.rows("Patients").getValue("10000000-0000-4000-8000-000000000003")
            assertEquals("Ana Pérez Núñez", patient["FullName"])
            assertEquals("1985-03-02 00:00:00.0000000", patient["BirthDate"])
            assertEquals(1L, patient["IsActive"])
            assertEquals(41L, patient["Age"])
            // Marcas de 27 caracteres; SyncedAtUtc se queda como lo escribió la nube
            assertEquals(27, (patient["UpdatedAtUtc"] as String).length)
            assertEquals(Transcript.SERVED_TIME, patient["SyncedAtUtc"])

            // Cada tabla termina en la marca de agua, guardada tal cual; se adoptaron la identidad y la generación
            assertEquals("${Transcript.SERVED_TIME}|", store.meta(SyncEngine.CURSOR_PREFIX + "Patients"))
            assertEquals("${Transcript.SERVED_TIME}|", store.meta(SyncEngine.CURSOR_PREFIX + "SyncTombstones"))
            assertEquals("<id:1>", store.meta(SyncEngine.CLINIC_ID))
            assertEquals("<id:2>", store.meta(SyncEngine.GENERATION))
            assertEquals("T1", store.meta(SyncEngine.DEVICE_CODE))
            assertEquals("AndroidTablet", store.meta(SyncEngine.DEVICE_ROLE))
            assertNull("Sin diferencias de manifiesto", store.meta(SyncEngine.READ_ONLY))
        }
    }

    /** La fila que grabó la PC es de un equipo con rol de PC: en Android no baja nada y no borra nada. */
    @Test
    fun laFilaGrabadaDeLaPcEsOtroRol() {
        RecordingReplay(Transcript.load("first-download")).use { replay ->
            replay.registryRow = replay.recordedRegistryRow()
            val store = InMemorySyncStore()
            assertEquals(SyncOutcome.WrongRole("WindowsDesktop"), engine(replay, store).sync(SyncTrigger.USER))
            assertTrue(replay.failures.joinToString("\n"), replay.failures.isEmpty())
            assertEquals("Ninguna petición de la grabación", emptyList<Int>(), replay.consumed)
            assertEquals(listOf(Statements.META_ALL, Statements.REGISTRY_OWN), replay.extrasServed)
        }
    }

    @Test
    fun empatesEnLaLlave() {
        RecordingReplay(Transcript.load("key-ties"), skipped = mapOf(1 to HEARTBEAT_IN_K3, 20 to PC_REGISTRATION)).use { replay ->
            val store = InMemorySyncStore()
            val outcome = engine(replay, store, pageSize = 2).sync(SyncTrigger.USER)

            assertTrue("$outcome", outcome is SyncOutcome.Completed)
            assertClean(replay)
            // Las cinco categorías con la misma hora: ninguna se pierde ni se repite
            assertEquals(
                (101..105).map { "10000000-0000-4000-8000-000000000$it" },
                store.rows("Categories").keys.toList(),
            )
        }
    }

    @Test
    fun incremental() {
        RecordingReplay(Transcript.load("incremental")).use { replay ->
            val store = syncedStore().apply {
                put("Patients", "10000000-0000-4000-8000-000000000003", "UpdatedAtUtc" to "2026-11-02 10:00:00.0000000", "SyncedAtUtc" to "2026-11-02 10:00:00.000", "Phone" to "5500000000", "FullName" to "Ana")
                put("Appointments", "10000000-0000-4000-8000-000000000005", "UpdatedAtUtc" to "2026-11-02 10:00:00.0000000", "SyncedAtUtc" to "2026-11-02 10:00:00.000", "PatientRecordSyncId" to "10000000-0000-4000-8000-000000000003", "Date" to "2026-12-01 00:00:00.0000000", "Status" to "Programada", "Reason" to "Control")
                put("Categories", "10000000-0000-4000-8000-000000000001", "UpdatedAtUtc" to "2026-11-02 10:00:00.0000000", "SyncedAtUtc" to "2026-11-02 10:00:00.000", "Name" to "Vieja")
                put("Products", "10000000-0000-4000-8000-000000000002", "UpdatedAtUtc" to "2026-11-02 10:00:00.0000000", "SyncedAtUtc" to "2026-11-02 10:00:00.000", "CategorySyncId" to "10000000-0000-4000-8000-000000000001")
                put("PatientConsultations", "10000000-0000-4000-8000-000000000004", "UpdatedAtUtc" to "2026-11-02 10:00:00.0000000", "SyncedAtUtc" to "2026-11-02 10:00:00.000", "PatientRecordSyncId" to "10000000-0000-4000-8000-000000000003")
            }
            val outcome = engine(replay, store).sync(SyncTrigger.AUTOMATIC)

            assertTrue("$outcome", outcome is SyncOutcome.Completed && !outcome.fullDownload)
            assertClean(replay)
            assertEquals("Una sola petición (8.6), con su fila de DeviceRegistry", 1, replay.httpRequests)
            assertEquals(listOf(Statements.REGISTRY_OWN), replay.extrasServed)

            // Llegan el paciente con su teléfono nuevo y la lápida de la cita
            val patient = store.rows("Patients").getValue("10000000-0000-4000-8000-000000000003")
            assertEquals("Ana Pérez Núñez", patient["FullName"])
            assertFalse("La cita se borró", "10000000-0000-4000-8000-000000000005" in store.rows("Appointments"))
            assertTrue(("PatientAppointment" to "10000000-0000-4000-8000-000000000005") in store.tombstones)
            assertEquals("${Transcript.SERVED_TIME}|", store.meta(SyncEngine.CURSOR_PREFIX + "Patients"))
            assertEquals("${Transcript.SERVED_TIME}|", store.meta(SyncEngine.CURSOR_PREFIX + "Sales"))
        }
    }

    @Test
    fun cambioDeGeneracion() {
        RecordingReplay(Transcript.load("generation-change")).use { replay ->
            val store = syncedStore().apply {
                put("Categories", "10000000-0000-4000-8000-00000000abcd", "UpdatedAtUtc" to "2026-11-02 10:00:00.0000000", "SyncedAtUtc" to "2026-11-02 10:00:00.000", "Name" to "De la nube anterior")
            }
            val engine = engine(replay, store)

            // generationReady todavía es la anterior: no se baja ni se aplica nada
            assertEquals(SyncOutcome.GenerationPending, engine.sync(SyncTrigger.AUTOMATIC))
            assertEquals("<id:2>", store.meta(SyncEngine.GENERATION))
            assertTrue("10000000-0000-4000-8000-00000000abcd" in store.rows("Categories"))
            assertEquals("2026-11-03 17:59:00.000|", store.meta(SyncEngine.CURSOR_PREFIX + "Categories"))

            // Ya terminó: se vacía, adopta la nueva y baja todo una sola vez, sin omitir sus filas
            val outcome = engine.sync(SyncTrigger.AUTOMATIC)
            assertTrue("$outcome", outcome is SyncOutcome.Completed && outcome.fullDownload)
            assertClean(replay)
            assertEquals("10000000-0000-4000-8000-000000000900", store.meta(SyncEngine.GENERATION))
            assertFalse("10000000-0000-4000-8000-00000000abcd" in store.rows("Categories"))
            assertEquals(listOf("<id:1>"), listOf(store.meta(SyncEngine.CLINIC_ID)))
            assertEquals("Solo una descarga completa", "1", store.meta("Usage:FullPulls"))
        }
    }

    @Test
    fun cuotaAgotada() {
        RecordingReplay(Transcript.load("blocked")).use { replay ->
            val store = syncedStore().apply {
                put("Categories", "10000000-0000-4000-8000-00000000abcd", "UpdatedAtUtc" to "2026-11-02 10:00:00.0000000", "SyncedAtUtc" to "2026-11-02 10:00:00.000")
            }
            val engine = engine(replay, store)

            // HTTP 200 con cada instrucción en error: una hora sin intentar
            val first = engine.sync(SyncTrigger.AUTOMATIC)
            assertEquals(SyncOutcome.QuotaPaused(clock.now.plus(Duration.ofHours(1))), first)
            assertEquals(1, replay.httpRequests)

            clock.advance(Duration.ofMinutes(59))
            assertTrue(engine.sync(SyncTrigger.AUTOMATIC) is SyncOutcome.QuotaPaused)
            assertEquals("Lo automático no llega a Turso", 1, replay.httpRequests)

            // Lo que pide el médico sigue intentando; HTTP 403 con BLOCKED en el cuerpo se trata igual
            assertTrue(engine.sync(SyncTrigger.USER) is SyncOutcome.QuotaPaused)
            assertEquals(2, replay.httpRequests)
            assertClean(replay)

            // No se borra ni se aparta nada
            assertEquals(1, store.rows("Categories").size)
            assertTrue(store.parked.isEmpty())
            assertEquals("2026-11-03 17:59:00.000|", store.meta(SyncEngine.CURSOR_PREFIX + "Categories"))
        }
    }

    @Test
    fun tokenAnulado() {
        RecordingReplay(Transcript.load("unauthorized")).use { replay ->
            val store = syncedStore().apply {
                put("Categories", "10000000-0000-4000-8000-00000000abcd", "UpdatedAtUtc" to "2026-11-02 10:00:00.0000000", "SyncedAtUtc" to "2026-11-02 10:00:00.000")
            }
            val engine = engine(replay, store, deviceId = "device-celular")

            assertEquals(SyncOutcome.TokenRejected(wiped = false), engine.sync(SyncTrigger.AUTOMATIC))
            assertClean(replay)
            assertEquals("No se borra nada con el primero", 1, store.rows("Categories").size)

            // Lo automático deja de intentar hasta confirmar a los 10 min
            clock.advance(Duration.ofMinutes(5))
            assertEquals(SyncOutcome.TokenRejected(wiped = false), engine.sync(SyncTrigger.AUTOMATIC))
            assertEquals(1, replay.httpRequests)
        }
    }
}
