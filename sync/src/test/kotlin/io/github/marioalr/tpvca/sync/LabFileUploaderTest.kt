package io.github.marioalr.tpvca.sync

import io.github.marioalr.tpvca.sync.engine.ConsumptionBrake
import io.github.marioalr.tpvca.sync.hrana.HranaConnection
import io.github.marioalr.tpvca.sync.hrana.HranaException
import io.github.marioalr.tpvca.sync.hrana.HranaValue
import io.github.marioalr.tpvca.sync.hrana.ResultSet
import io.github.marioalr.tpvca.sync.hrana.Snapshot
import io.github.marioalr.tpvca.sync.hrana.Statement
import io.github.marioalr.tpvca.sync.link.LabFileRules
import io.github.marioalr.tpvca.sync.link.LabFileUploader
import io.github.marioalr.tpvca.sync.link.LabUploadBytes
import io.github.marioalr.tpvca.sync.link.LabUploadFiles
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.TreeMap

/** Subir los resultados que la tablet agregó (`UploadLabFileAsync` de la PC): el SQL de `lab-files.json`, continuar tras un corte, el tope de la nube y el freno del día. */
class LabFileUploaderTest {
    private fun content(size: Int, seed: Int = 0) = ByteArray(size) { ((it + seed) % 251).toByte() }

    private val clock: Clock = Clock.fixed(Instant.parse("2026-11-08T15:00:00Z"), ZoneOffset.UTC)

    /** Los archivos pendientes en memoria, como la caché cifrada de la tablet. */
    private class Files(vararg files: ByteArray) : LabUploadFiles {
        val pending = LinkedHashMap<String, ByteArray>().apply { for (f in files) put(LabFileRules.sha256(f), f) }

        /** Cuándo se guardó cada uno; sin decir, hace mucho. */
        val since = HashMap<String, Instant>()
        val settled = ArrayList<String>()
        var damaged = setOf<String>()
        var alive = true

        override fun ticket() = 1L

        override fun alive(ticket: Long) = alive

        override fun hasPending() = pending.isNotEmpty()

        override fun pending(ticket: Long) = pending.keys.toList()

        override fun pendingSince(ticket: Long, sha256: String): Instant? = if (sha256 in pending) since[sha256] ?: Instant.parse("2026-11-01T00:00:00Z") else null

        override fun read(ticket: Long, sha256: String): ByteArray? = if (sha256 in damaged) null else pending[sha256]

        override fun settle(ticket: Long, sha256: String) {
            pending.remove(sha256)
            settled += sha256
        }

        override fun settleOrphan(ticket: Long, sha256: String, stillOrphan: () -> Boolean) {
            if (stillOrphan()) settle(ticket, sha256)
        }
    }

    /** Una nube con `LabBlobs` y `LabBlobChunks`: responde a lo que manda el subidor. */
    private class Cloud(var usedBytes: Long = 0, var pageSize: Long = 4096, var pages: Long = 1000, var freePages: Long = 0, var failAfter: Int? = null) : HranaConnection {
        class Blob(val size: Long, val chunkSize: Long, val chunkCount: Int, val mime: String, val device: String, var completed: String? = null, var evicted: String? = null)

        val blobs = LinkedHashMap<String, Blob>()
        val chunks = LinkedHashMap<Pair<String, Int>, ByteArray>()
        val requests = ArrayList<List<Statement>>()
        var batches = 0

        private fun row(vararg pairs: Pair<String, HranaValue>) = TreeMap<String, HranaValue>(String.CASE_INSENSITIVE_ORDER).apply { putAll(pairs) }

        private fun text(value: String?) = value?.let { HranaValue.Text(it) } ?: HranaValue.Null

        private fun run(statement: Statement): ResultSet {
            val sql = statement.sql
            val args = statement.args
            fun sha() = (args[0] as HranaValue.Text).value
            return when (sql) {
                LabFileRules.STATE_SQL -> {
                    val b = blobs[sha()] ?: return ResultSet(emptyList())
                    ResultSet(
                        listOf(
                            row(
                                "SizeBytes" to HranaValue.Integer(b.size.toString()), "ChunkSize" to HranaValue.Integer(b.chunkSize.toString()), "ChunkCount" to HranaValue.Integer(b.chunkCount.toString()),
                                "MimeType" to HranaValue.Text(b.mime), "OriginDeviceId" to HranaValue.Text(b.device), "CompletedAtUtc" to text(b.completed), "ArchivedAtUtc" to HranaValue.Null, "EvictedAtUtc" to text(b.evicted),
                            ),
                        ),
                    )
                }
                LabFileRules.EXISTING_CHUNKS_SQL -> ResultSet(chunks.keys.filter { it.first == sha() }.map { row("ChunkIndex" to HranaValue.Integer(it.second.toString())) })
                LabFileRules.USAGE_SQL -> ResultSet(listOf(row("Files" to HranaValue.Integer(blobs.size.toString()), "Bytes" to HranaValue.Integer(usedBytes.toString()))))
                "PRAGMA page_count;" -> ResultSet(listOf(row("page_count" to HranaValue.Integer(pages.toString()))))
                "PRAGMA freelist_count;" -> ResultSet(listOf(row("freelist_count" to HranaValue.Integer(freePages.toString()))))
                "PRAGMA page_size;" -> ResultSet(listOf(row("page_size" to HranaValue.Integer(pageSize.toString()))))
                LabFileRules.REGISTER_SQL -> {
                    if (sha() !in blobs) {
                        blobs[sha()] = Blob((args[1] as HranaValue.Integer).value.toLong(), (args[2] as HranaValue.Integer).value.toLong(), (args[3] as HranaValue.Integer).value.toInt(), (args[4] as HranaValue.Text).value, (args[5] as HranaValue.Text).value)
                        usedBytes += blobs.getValue(sha()).size
                    }
                    ResultSet(emptyList(), 1)
                }
                LabFileRules.INSERT_CHUNK_SQL -> {
                    val key = sha() to (args[1] as HranaValue.Integer).value.toInt()
                    if (key !in chunks) chunks[key] = (args[2] as HranaValue.Blob).bytes
                    ResultSet(emptyList(), 1)
                }
                LabFileRules.CLOSE_SQL -> {
                    val b = blobs[sha()]
                    val have = chunks.keys.count { it.first == sha() }
                    if (b != null && b.completed == null && have == b.chunkCount) {
                        b.completed = "2026-11-08 15:00:00.000"
                        ResultSet(emptyList(), 1)
                    } else {
                        ResultSet(emptyList(), 0)
                    }
                }
                else -> error("SQL inesperado: $sql")
            }
        }

        private fun check() {
            val limit = failAfter ?: return
            if (requests.size >= limit) throw HranaException(HranaException.NETWORK_ERROR, "Sin conexión")
        }

        override fun query(statements: List<Statement>): List<ResultSet> {
            check()
            requests += statements
            return statements.map(::run)
        }

        override fun snapshot(before: List<Statement>, statements: List<Statement>): Snapshot {
            check()
            requests += statements
            batches++
            return Snapshot(before.map(::run), statements.map(::run))
        }

        fun stored(sha: String): ByteArray = chunks.filterKeys { it.first == sha }.toSortedMap(compareBy { it.second }).values.fold(ByteArray(0)) { acc, piece -> acc + piece }
    }

    private fun store(vararg shas: String): InMemorySyncStore {
        val store = InMemorySyncStore()
        for ((i, sha) in shas.withIndex()) {
            store.put("LabResults", "r$i", "ContentSha256" to sha, "MimeType" to "application/pdf", "ResultDate" to "2026-11-0${i + 1}", "CreatedAt" to "2026-11-0${i + 1}T10:00:00", "PatientRecordSyncId" to "p1", "Title" to "BH")
        }
        return store
    }

    private fun uploader(cloud: Cloud, store: InMemorySyncStore, files: Files, maxBytesPerDay: Long = LabFileRules.ANDROID_UP_BYTES_PER_DAY, cap: Long = LabFileRules.CLOUD_CAP_BYTES, stop: Long = LabFileRules.CLOUD_SPACE_STOP_BYTES) =
        LabFileUploader(cloud, store, files, "tablet-1", clock, maxBytesPerDay = maxBytesPerDay, cloudCap = cap, cloudSpaceStop = stop)

    @Test
    fun registraSubeLosTrozosYCierraEnElUltimoBatch() {
        val file = content(1_200_000)
        val sha = LabFileRules.sha256(file)
        val cloud = Cloud()
        val files = Files(file)
        val store = store(sha)
        val report = uploader(cloud, store, files).run()
        assertEquals(1, report.uploaded)
        assertFalse(report.failed)
        assertNull(report.notice)
        val blob = cloud.blobs.getValue(sha)
        assertEquals(1_200_000L, blob.size)
        assertEquals(3, blob.chunkCount)
        assertEquals("application/pdf", blob.mime)
        assertEquals("tablet-1", blob.device)
        assertTrue("Cerrado con el último trozo", blob.completed != null)
        assertArrayEquals(file, cloud.stored(sha))
        assertEquals("El último trozo y el cierre van juntos en una transacción", 1, cloud.batches)
        // Primero el estado y los trozos que hay, en una petición
        assertEquals(listOf(LabFileRules.STATE_SQL, LabFileRules.EXISTING_CHUNKS_SQL), cloud.requests[0].map { it.sql })
        assertEquals(listOf(sha), files.settled)
        assertTrue(files.pending.isEmpty())
        // El freno del día cuenta el base64 de lo que viajó
        assertEquals(LabFileRules.travelBytes(LabFileRules.CHUNK_SIZE.toLong()) * 2 + LabFileRules.travelBytes(1_200_000L - 2 * LabFileRules.CHUNK_SIZE), LabUploadBytes(store, clock).today())
        assertTrue(ConsumptionBrake(store, clock).let { !it.isTripped })
    }

    @Test
    fun unCorteSigueDespuesConLosTrozosQueFaltan() {
        val file = content(1_200_000, seed = 7)
        val sha = LabFileRules.sha256(file)
        // Estado, tope, registro y el primer trozo pasan; el segundo ya no
        val cloud = Cloud(failAfter = 4)
        val files = Files(file)
        val store = store(sha)
        val first = uploader(cloud, store, files).run()
        assertTrue(first.failed)
        assertEquals(0, first.uploaded)
        assertTrue(first.notice!!.startsWith("Los archivos de laboratorio se siguen después"))
        assertTrue("Sigue pendiente", files.pending.containsKey(sha))
        assertNull(cloud.blobs.getValue(sha).completed)
        assertEquals(1, cloud.chunks.size)
        // Vuelve la red: solo lo que falta, y se cierra
        cloud.failAfter = null
        val second = uploader(cloud, store, files).run()
        assertEquals(1, second.uploaded)
        assertFalse(second.failed)
        assertEquals(3, cloud.chunks.size)
        assertArrayEquals(file, cloud.stored(sha))
        assertTrue(cloud.blobs.getValue(sha).completed != null)
        assertEquals(1, cloud.requests.count { it.any { s -> s.sql == LabFileRules.REGISTER_SQL } })
    }

    @Test
    fun loQueYaEstaOSeDesalojoNoSeVuelveASubirYLoDanadoSeCuenta() {
        val done = content(1000, seed = 1)
        val evicted = content(1000, seed = 2)
        val damaged = content(1000, seed = 3)
        val cloud = Cloud()
        cloud.blobs[LabFileRules.sha256(done)] = Cloud.Blob(1000, LabFileRules.CHUNK_SIZE.toLong(), 1, "application/pdf", "pc", completed = "ya")
        cloud.blobs[LabFileRules.sha256(evicted)] = Cloud.Blob(1000, LabFileRules.CHUNK_SIZE.toLong(), 1, "application/pdf", "pc", evicted = "se fue")
        val files = Files(done, evicted, damaged)
        files.damaged = setOf(LabFileRules.sha256(damaged))
        val report = uploader(cloud, store(LabFileRules.sha256(done), LabFileRules.sha256(evicted), LabFileRules.sha256(damaged)), files).run()
        assertEquals(0, report.uploaded)
        assertEquals(1, report.damaged)
        assertEquals("Los dos se sueltan sin gastar la nube", setOf(LabFileRules.sha256(done), LabFileRules.sha256(evicted)), files.settled.toSet())
        assertTrue(cloud.chunks.isEmpty())
        assertTrue("El dañado se queda donde está", files.pending.containsKey(LabFileRules.sha256(damaged)))
    }

    @Test
    fun loQueNingunResultadoNombraSeSueltaSinSubir() {
        val orphan = content(500)
        val files = Files(orphan)
        val cloud = Cloud()
        val report = uploader(cloud, InMemorySyncStore(), files).run()
        assertEquals(0, report.uploaded)
        assertTrue(cloud.requests.isEmpty())
        assertEquals(listOf(LabFileRules.sha256(orphan)), files.settled)
    }

    /**
     * Revisión de K7: la tablet guarda el archivo antes que su fila. Una vuelta que cae justo entre los dos ve un pendiente sin fila: no es huérfano todavía, se queda pendiente, y
     * en cuanto la fila existe sube. Solo pasado el margen sin fila se suelta.
     */
    @Test
    fun unPendienteRecienGuardadoSinFilaEsperaASuFilaYLuegoSube() {
        val file = content(800, seed = 9)
        val sha = LabFileRules.sha256(file)
        val files = Files(file)
        files.since[sha] = clock.instant().minusSeconds(5)
        val cloud = Cloud()
        val store = InMemorySyncStore()
        // 1. Archivo guardado, fila todavía no: la vuelta no lo toca
        val between = uploader(cloud, store, files).run()
        assertEquals(0, between.uploaded)
        assertTrue("Sigue pendiente", files.pending.containsKey(sha))
        assertTrue(files.settled.isEmpty())
        assertTrue(cloud.requests.isEmpty())
        // 2. Se escribe la fila: la siguiente vuelta lo sube
        store.put("LabResults", "r0", "ContentSha256" to sha, "MimeType" to "application/pdf", "ResultDate" to "2026-11-08", "CreatedAt" to "2026-11-08T14:59:55", "PatientRecordSyncId" to "p1", "Title" to "BH")
        val after = uploader(cloud, store, files).run()
        assertEquals(1, after.uploaded)
        assertArrayEquals(file, cloud.stored(sha))
        assertTrue(cloud.blobs.getValue(sha).completed != null)
    }

    @Test
    fun unPendienteSinFilaSeSueltaSoloPasadoElMargen() {
        val file = content(700, seed = 4)
        val sha = LabFileRules.sha256(file)
        val files = Files(file)
        files.since[sha] = clock.instant().minus(LabFileUploader.ORPHAN_MARGIN).plusSeconds(1)
        uploader(Cloud(), InMemorySyncStore(), files).run()
        assertTrue("Un segundo antes del margen sigue pendiente", files.pending.containsKey(sha))
        files.since[sha] = clock.instant().minus(LabFileUploader.ORPHAN_MARGIN)
        uploader(Cloud(), InMemorySyncStore(), files).run()
        assertEquals(listOf(sha), files.settled)
    }

    @Test
    fun conElTopeDeLaNubeElArchivoSeQuedaEnElEquipo() {
        val file = content(3000)
        val files = Files(file)
        val cloud = Cloud(usedBytes = 1_999_999_000)
        val report = uploader(cloud, store(LabFileRules.sha256(file)), files).run()
        assertEquals(1, report.waitingForCap)
        assertTrue(report.notice!!.contains("llegó a su tope"))
        assertTrue(cloud.blobs.isEmpty())
        assertTrue(files.pending.containsKey(LabFileRules.sha256(file)))
        // Y con la base casi llena, aunque el tope de laboratorio no se haya alcanzado
        val full = Cloud(pages = 1_000_000, pageSize = 4096)
        val again = uploader(full, store(LabFileRules.sha256(file)), Files(file)).run()
        assertEquals(1, again.waitingForCap)
        assertTrue(again.notice!!.contains("casi llena"))
    }

    @Test
    fun elFrenoDelDiaDetieneLaSubidaYSigueManana() {
        val a = content(1_100_000, seed = 1)
        val b = content(1000, seed = 2)
        val files = Files(a, b)
        val store = store(LabFileRules.sha256(a), LabFileRules.sha256(b))
        val cloud = Cloud()
        // Lo más reciente primero: el chico (b) sube entero; del grande cabe el primer trozo y nada más
        val report = uploader(cloud, store, files, maxBytesPerDay = LabFileRules.travelBytes(LabFileRules.CHUNK_SIZE.toLong())).run()
        assertEquals(1, report.uploaded)
        assertTrue(report.notice!!.contains("tope de subida"))
        assertEquals(2, cloud.chunks.size)
        assertEquals(listOf(LabFileRules.sha256(a)), files.pending.keys.toList())
        // Otro día: lo que falta
        val tomorrow = Clock.fixed(Instant.parse("2026-11-09T15:00:00Z"), ZoneOffset.UTC)
        val next = LabFileUploader(cloud, store, files, "tablet-1", tomorrow).run()
        assertEquals(1, next.uploaded)
        assertTrue(files.pending.isEmpty())
        assertArrayEquals(a, cloud.stored(LabFileRules.sha256(a)))
    }

    @Test
    fun pausadoConElFrenoOElTokenRechazado() {
        val store = InMemorySyncStore()
        assertFalse(LabFileUploader.paused(store, clock))
        store.apply(io.github.marioalr.tpvca.sync.engine.StoreChanges(meta = mapOf(io.github.marioalr.tpvca.sync.engine.SyncEngine.AUTH_FIRST_401 to "1")))
        assertTrue(LabFileUploader.paused(store, clock))
    }
}
