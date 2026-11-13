package io.github.marioalr.tpvca.sync

import io.github.marioalr.tpvca.sync.hrana.HranaConnection
import io.github.marioalr.tpvca.sync.hrana.HranaException
import io.github.marioalr.tpvca.sync.hrana.HranaValue
import io.github.marioalr.tpvca.sync.hrana.ResultSet
import io.github.marioalr.tpvca.sync.hrana.Snapshot
import io.github.marioalr.tpvca.sync.hrana.Statement
import io.github.marioalr.tpvca.sync.link.LabFetch
import io.github.marioalr.tpvca.sync.link.LabFileDownloader
import io.github.marioalr.tpvca.sync.link.LabFileRules
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TreeMap

/** `golden/lab-files.json`: cómo se parte un archivo, el SQL literal de bajar y de subir, y los topes. Subir lo prueba `LabFileUploaderTest`. */
class LabFilesGoldenTest {
    private val cases = Json.parseToJsonElement(Contracts.file("golden/lab-files.json").readText()).jsonObject.getValue("cases").jsonArray.map { it.jsonObject }

    private fun content(size: Int) = ByteArray(size) { (it % 251).toByte() }

    @Test
    fun losTrozosSonLosDeLaPc() {
        val chunks = cases.filter { it.getValue("name").jsonPrimitive.content.startsWith("trozos") }
        assertEquals(4, chunks.size)
        for (case in chunks) {
            val size = case.getValue("input").jsonObject.getValue("sizeBytes").jsonPrimitive.int
            val expected = case.getValue("expected").jsonObject
            val bytes = content(size)
            assertEquals(expected.getValue("sha256").jsonPrimitive.content, LabFileRules.sha256(bytes))
            assertEquals(expected.getValue("chunkSize").jsonPrimitive.int, LabFileRules.CHUNK_SIZE)
            assertEquals(expected.getValue("chunkCount").jsonPrimitive.int, LabFileRules.chunkCount(size.toLong()))
            val actual = LabFileRules.chunks(bytes)
            val rows = expected.getValue("chunks").jsonArray.map { it.jsonObject }
            assertEquals(rows.size, actual.size)
            for ((row, chunk) in rows.zip(actual)) {
                assertEquals(row.getValue("chunkIndex").jsonPrimitive.int, chunk.index)
                assertEquals(row.getValue("size").jsonPrimitive.int, chunk.size)
                assertEquals(row.getValue("sha256").jsonPrimitive.content, chunk.sha256)
            }
        }
    }

    @Test
    fun elSqlDeBajarYSubirEsLiteral() {
        val sql = cases.first { it.getValue("name").jsonPrimitive.content == "sql" }.getValue("expected").jsonObject
        assertEquals(sql.getValue("state").jsonPrimitive.content, LabFileRules.STATE_SQL)
        assertEquals(sql.getValue("readChunks").jsonPrimitive.content, LabFileRules.READ_CHUNKS_SQL)
        // Subir (K7, la tablet): la PC principal se queda con archivar, desalojar y los huérfanos
        assertEquals(sql.getValue("existingChunks").jsonPrimitive.content, LabFileRules.EXISTING_CHUNKS_SQL)
        assertEquals(sql.getValue("usage").jsonPrimitive.content, LabFileRules.USAGE_SQL)
        assertEquals(sql.getValue("cloudSpace").jsonArray.map { it.jsonPrimitive.content }, LabFileRules.CLOUD_SPACE_SQL)
        assertEquals(sql.getValue("register").jsonPrimitive.content, LabFileRules.REGISTER_SQL)
        assertEquals(sql.getValue("insertChunk").jsonPrimitive.content, LabFileRules.INSERT_CHUNK_SQL)
        assertEquals(sql.getValue("close").jsonPrimitive.content, LabFileRules.CLOSE_SQL)
    }

    @Test
    fun losTopes() {
        val limits = cases.first { it.getValue("name").jsonPrimitive.content == "topes" }.getValue("expected").jsonObject
        assertEquals(limits.getValue("chunksPerRead").jsonPrimitive.int, LabFileRules.CHUNKS_PER_READ)
        assertEquals(limits.getValue("maxFileBytes").jsonPrimitive.long, LabFileRules.MAX_FILE_BYTES)
        assertEquals(limits.getValue("largePdfBytes").jsonPrimitive.long, LabFileRules.LARGE_PDF_BYTES)
        assertEquals(limits.getValue("maxLongSidePixels").jsonPrimitive.int, LabFileRules.MAX_LONG_SIDE_PIXELS)
        assertEquals(limits.getValue("jpegQuality").jsonPrimitive.int, LabFileRules.JPEG_QUALITY)
        assertEquals(limits.getValue("maxPhotos").jsonPrimitive.int, LabFileRules.MAX_PHOTOS)
        assertEquals(limits.getValue("mimeTypes").jsonArray.map { it.jsonPrimitive.content }, listOf(LabFileRules.PDF_MIME_TYPE, LabFileRules.JPEG_MIME_TYPE))
        assertEquals(limits.getValue("cloudCapBytes").jsonPrimitive.long, LabFileRules.CLOUD_CAP_BYTES)
        assertEquals(limits.getValue("cloudSpaceStopBytes").jsonPrimitive.long, LabFileRules.CLOUD_SPACE_STOP_BYTES)
        assertEquals(limits.getValue("androidBytesPerDay").jsonObject.getValue("up").jsonPrimitive.long, LabFileRules.ANDROID_UP_BYTES_PER_DAY)
        // Lo que viaja de un trozo es su base64
        assertEquals(4L, LabFileRules.travelBytes(1))
        assertEquals(4L, LabFileRules.travelBytes(3))
        assertEquals(8L, LabFileRules.travelBytes(4))
    }

    /** Una nube con un archivo: responde lo que lee `LabFileDownloader`. */
    private class Cloud(val bytes: ByteArray, val declaredSize: Long = bytes.size.toLong(), val completed: String? = "2026-11-03", val evicted: String? = null, val exists: Boolean = true, val corrupt: Boolean = false, val fail: Boolean = false) : HranaConnection {
        val requests = mutableListOf<Statement>()

        private fun row(vararg pairs: Pair<String, HranaValue>) = TreeMap<String, HranaValue>(String.CASE_INSENSITIVE_ORDER).apply { putAll(pairs) }

        override fun query(statements: List<Statement>): List<ResultSet> {
            if (fail) throw HranaException(HranaException.NETWORK_ERROR, "Sin conexión")
            val statement = statements.single()
            requests.add(statement)
            return listOf(
                if (statement.sql == LabFileRules.STATE_SQL) {
                    if (!exists) {
                        ResultSet(emptyList())
                    } else {
                        ResultSet(
                            listOf(
                                row(
                                    "SizeBytes" to HranaValue.Integer(declaredSize.toString()), "ChunkCount" to HranaValue.Integer(LabFileRules.chunkCount(bytes.size.toLong()).toString()),
                                    "MimeType" to HranaValue.Text("application/pdf"), "CompletedAtUtc" to (completed?.let { HranaValue.Text(it) } ?: HranaValue.Null), "EvictedAtUtc" to (evicted?.let { HranaValue.Text(it) } ?: HranaValue.Null),
                                ),
                            ),
                        )
                    }
                } else {
                    val first = (statement.args[1] as HranaValue.Integer).value.toInt()
                    ResultSet(
                        (first until minOf(first + 4, LabFileRules.chunkCount(bytes.size.toLong()))).map { i ->
                            val from = i * LabFileRules.CHUNK_SIZE
                            var piece = bytes.copyOfRange(from, minOf(from + LabFileRules.CHUNK_SIZE, bytes.size))
                            if (corrupt) piece = piece.copyOf().also { it[0] = (it[0] + 1).toByte() }
                            row("ChunkIndex" to HranaValue.Integer(i.toString()), "Data" to HranaValue.Blob(piece))
                        },
                    )
                },
            )
        }

        override fun snapshot(before: List<Statement>, statements: List<Statement>): Snapshot = error("No se usa")
    }

    private val sample = content(3_000_000)
    private val sha = LabFileRules.sha256(sample)

    @Test
    fun bajaDeCuatroEnCuatroYRevisaElArchivo() {
        val cloud = Cloud(sample)
        val fetched = LabFileDownloader(cloud).download(sha) as LabFetch.Ok
        assertArrayEquals(sample, fetched.bytes)
        assertEquals("application/pdf", fetched.mimeType)
        // 3 MB = 6 trozos: el estado y dos lecturas, desde el trozo 0 y desde el 4
        assertEquals(listOf("0", "4"), cloud.requests.drop(1).map { (it.args[1] as HranaValue.Integer).value })
    }

    /** Revisión de K4: un dato dañado de la nube no tumba la app ni se junta sin tope. */
    @Test
    fun loDanadoDeLaNubeNoLanzaNiCreceSinTope() {
        assertEquals("Un tamaño negativo", LabFetch.Broken, LabFileDownloader(Cloud(sample, declaredSize = -1)).download(sha))
        assertEquals("Mayor que el tope", LabFetch.Broken, LabFileDownloader(Cloud(sample, declaredSize = LabFileRules.MAX_FILE_BYTES + 1)).download(sha))
        val small = Cloud(sample, declaredSize = 10)
        assertEquals("Trozos que pasan de lo declarado", LabFetch.Broken, LabFileDownloader(small).download(sha))
        assertEquals("Se detiene en la primera lectura", 2, small.requests.size)
        val notHex = Cloud(sample)
        assertEquals("Un SHA-256 que no lo es", LabFetch.Broken, LabFileDownloader(notHex).download("../../base"))
        assertTrue("Ni se pregunta", notHex.requests.isEmpty())
    }

    @Test
    fun cadaEstadoDiceLoQueDiceLaPc() {
        assertEquals(LabFetch.NotInCloud, LabFileDownloader(Cloud(sample, exists = false)).download(sha))
        assertEquals(LabFetch.Evicted, LabFileDownloader(Cloud(sample, evicted = "x")).download(sha))
        assertEquals(LabFetch.Uploading, LabFileDownloader(Cloud(sample, completed = null)).download(sha))
        assertEquals(LabFetch.Broken, LabFileDownloader(Cloud(sample, corrupt = true)).download(sha))
        val failed = LabFileDownloader(Cloud(sample, fail = true)).download(sha)
        assertTrue(failed is LabFetch.Failed)
    }
}
