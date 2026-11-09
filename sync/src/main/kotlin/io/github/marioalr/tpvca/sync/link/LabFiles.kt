package io.github.marioalr.tpvca.sync.link

import io.github.marioalr.tpvca.sync.hrana.HranaConnection
import io.github.marioalr.tpvca.sync.hrana.HranaException
import io.github.marioalr.tpvca.sync.hrana.HranaValue
import io.github.marioalr.tpvca.sync.hrana.Statement
import io.github.marioalr.tpvca.sync.hrana.ValueCodec
import io.github.marioalr.tpvca.sync.hrana.text
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Los archivos de laboratorio (regla «Resultados de laboratorio», paso 8.12b, `TursoSyncService.LabFiles.cs`). Vectores: `contracts/golden/lab-files.json`. El SQL es literal.
 * Subir es solo de la tablet ([LabFileUploader]); el celular solo baja. Lo de la PC principal (archivar, desalojar, huérfanos) no se porta: Kotlin nunca desaloja ni borra trozos.
 */
object LabFileRules {
    const val CHUNK_SIZE = 524_288
    const val CHUNKS_PER_READ = 4
    const val MAX_FILE_BYTES = 10_485_760L
    const val LARGE_PDF_BYTES = 3_145_728L
    const val MAX_LONG_SIDE_PIXELS = 2000
    const val JPEG_QUALITY = 80
    const val MAX_PHOTOS = 20
    const val PDF_MIME_TYPE = "application/pdf"
    const val JPEG_MIME_TYPE = "image/jpeg"

    /** El tope de los resultados en la nube. */
    const val CLOUD_CAP_BYTES = 2_000_000_000L

    /** Si toda la base pasa de esto, no se sube ningún archivo. */
    const val CLOUD_SPACE_STOP_BYTES = 4_000_000_000L

    /** El freno de subida de la tablet: 150 MB de base64 al día. */
    const val ANDROID_UP_BYTES_PER_DAY = 150_000_000L

    /** `state`. */
    const val STATE_SQL = "SELECT SizeBytes, ChunkSize, ChunkCount, MimeType, OriginDeviceId, CompletedAtUtc, ArchivedAtUtc, EvictedAtUtc FROM LabBlobs WHERE Sha256 = ?;"

    /** `readChunks`: del primer trozo que falta, de [CHUNKS_PER_READ] en [CHUNKS_PER_READ]. */
    const val READ_CHUNKS_SQL = "SELECT ChunkIndex, Data FROM LabBlobChunks WHERE Sha256 = ? AND ChunkIndex >= ? ORDER BY ChunkIndex LIMIT 4;"

    /** `existingChunks`. */
    const val EXISTING_CHUNKS_SQL = "SELECT ChunkIndex FROM LabBlobChunks WHERE Sha256 = ?;"

    /** `usage`. */
    const val USAGE_SQL = "SELECT COUNT(*) AS Files, ifnull(SUM(SizeBytes), 0) AS Bytes FROM LabBlobs WHERE EvictedAtUtc IS NULL;"

    /** `cloudSpace`: (page_count − freelist_count) × page_size. */
    val CLOUD_SPACE_SQL = listOf("PRAGMA page_count;", "PRAGMA freelist_count;", "PRAGMA page_size;")

    /** `register`: Sha256, SizeBytes, ChunkSize, ChunkCount, MimeType, OriginDeviceId. */
    const val REGISTER_SQL = "INSERT INTO LabBlobs (Sha256, SizeBytes, ChunkSize, ChunkCount, MimeType, CreatedAtUtc, OriginDeviceId) VALUES (?, ?, ?, ?, ?, strftime('%Y-%m-%d %H:%M:%f','now'), ?) ON CONFLICT(Sha256) DO NOTHING;"

    /** `insertChunk`: Sha256, ChunkIndex, Data. */
    const val INSERT_CHUNK_SQL = "INSERT INTO LabBlobChunks (Sha256, ChunkIndex, Data) VALUES (?, ?, ?) ON CONFLICT(Sha256, ChunkIndex) DO NOTHING;"

    /** `close`: Sha256, Sha256. Solo cierra si ya están todos sus trozos. */
    const val CLOSE_SQL = "UPDATE LabBlobs SET CompletedAtUtc = strftime('%Y-%m-%d %H:%M:%f','now') WHERE Sha256 = ? AND CompletedAtUtc IS NULL AND (SELECT COUNT(*) FROM LabBlobChunks WHERE Sha256 = ?) = ChunkCount;"

    /** Lo que viaja de un trozo: su base64, 4 × ceil(bytes / 3). Es lo que cuenta el freno. */
    fun travelBytes(bytes: Long): Long = 4 * ((bytes + 2) / 3)

    data class Chunk(val index: Int, val size: Int, val sha256: String)

    private val SHA256 = Regex("^[0-9a-fA-F]{64}$")

    /** `ContentSha256` viene de la nube: antes de usarlo en una ruta o una consulta. */
    fun isSha256(text: String?): Boolean = text != null && SHA256.matches(text)

    fun chunkCount(sizeBytes: Long): Int = ((sizeBytes + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt()

    /** Cómo se parte [content]: el último trozo puede ser más chico. */
    fun chunks(content: ByteArray): List<Chunk> = (0 until chunkCount(content.size.toLong())).map { index ->
        val from = index * CHUNK_SIZE
        val to = minOf(from + CHUNK_SIZE, content.size)
        Chunk(index, to - from, sha256(content, from, to))
    }

    fun sha256(content: ByteArray, from: Int = 0, to: Int = content.size): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(content, from, to - from)
        return digest.digest().joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
    }
}

/** Cómo terminó bajar un archivo. [message] es el mismo de la PC. */
sealed class LabFetch(open val message: String?) {
    data class Ok(val bytes: ByteArray, val mimeType: String) : LabFetch(null)

    data object NotInCloud : LabFetch("Este resultado todavía no llega a la nube: el equipo donde se agregó lo sube al sincronizar.")

    data object Evicted : LabFetch("Este resultado ya no está en la nube (se liberó espacio): está en el archivo de la PC principal y en su respaldo externo.")

    data object Uploading : LabFetch("Este resultado se está subiendo desde otro equipo; intente en unos minutos.")

    data object Broken : LabFetch("El archivo de este resultado llegó dañado de la nube.")

    /** [detail] ya pasó por [io.github.marioalr.tpvca.sync.hrana.Redactor]: es lo que dice [HranaException]. */
    data class Failed(val detail: String?) : LabFetch("No se pudo bajar el resultado de la nube" + (detail?.let { ": $it" } ?: "."))
}

/** Baja un archivo completo a pedido: abrir un resultado no cuenta para el freno de bytes (regla de la PC, `lab-files.json`). Bloquea: corre fuera del hilo principal. */
class LabFileDownloader(private val connection: HranaConnection) {
    fun download(sha256: String): LabFetch {
        if (!LabFileRules.isSha256(sha256)) return LabFetch.Broken
        try {
            val args = listOf<HranaValue>(HranaValue.Text(sha256))
            val state = connection.query(listOf(Statement(LabFileRules.STATE_SQL, args))).single().rows.firstOrNull() ?: return LabFetch.NotInCloud
            if (!state.text("EvictedAtUtc").isNullOrEmpty()) return LabFetch.Evicted
            if (state.text("CompletedAtUtc").isNullOrEmpty()) return LabFetch.Uploading
            val size = state.text("SizeBytes")?.toLongOrNull() ?: return LabFetch.Broken
            val chunkCount = state.text("ChunkCount")?.toIntOrNull() ?: return LabFetch.Broken
            if (size < 0 || size > LabFileRules.MAX_FILE_BYTES || chunkCount < 0) return LabFetch.Broken

            val content = ByteArrayOutputStream(size.toInt())
            var first = 0
            while (first < chunkCount) {
                val statement = Statement(LabFileRules.READ_CHUNKS_SQL, listOf(HranaValue.Text(sha256), HranaValue.Integer(first.toString())))
                val rows = connection.query(listOf(statement)).single().rows
                val expected = minOf(LabFileRules.CHUNKS_PER_READ, chunkCount - first)
                for (i in 0 until expected) {
                    val row = rows.getOrNull(i) ?: return LabFetch.Broken
                    if (row.text("ChunkIndex")?.toIntOrNull() != first + i) return LabFetch.Broken
                    val piece = ValueCodec.bytesOf(row.getValue("Data"))
                    // Lo que llega no se junta sin tope: nunca más de lo que el archivo dijo medir
                    if (content.size().toLong() + piece.size > size) return LabFetch.Broken
                    content.write(piece)
                }
                first += LabFileRules.CHUNKS_PER_READ
            }
            val bytes = content.toByteArray()
            if (bytes.size.toLong() != size || LabFileRules.sha256(bytes) != sha256.lowercase(java.util.Locale.ROOT)) return LabFetch.Broken
            return LabFetch.Ok(bytes, state.text("MimeType").orEmpty())
        } catch (e: HranaException) {
            return LabFetch.Failed(e.message)
        } catch (_: RuntimeException) {
            // Una fila que no se puede leer no tumba la app: el archivo llegó dañado
            return LabFetch.Broken
        }
    }
}
