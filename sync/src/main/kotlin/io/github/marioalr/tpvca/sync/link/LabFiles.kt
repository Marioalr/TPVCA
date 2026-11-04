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
 * Los archivos de laboratorio al bajarlos (regla «Resultados de laboratorio», paso 8.12b, `TursoSyncService.LabFiles.cs`). Vectores:
 * `contracts/golden/lab-files.json`. El SQL es literal. El celular nunca sube ni archiva: lo de subir es de K7.
 */
object LabFileRules {
    const val CHUNK_SIZE = 524_288
    const val CHUNKS_PER_READ = 4
    const val MAX_FILE_BYTES = 10_485_760L

    /** `state`. */
    const val STATE_SQL = "SELECT SizeBytes, ChunkSize, ChunkCount, MimeType, OriginDeviceId, CompletedAtUtc, ArchivedAtUtc, EvictedAtUtc FROM LabBlobs WHERE Sha256 = ?;"

    /** `readChunks`: del primer trozo que falta, de [CHUNKS_PER_READ] en [CHUNKS_PER_READ]. */
    const val READ_CHUNKS_SQL = "SELECT ChunkIndex, Data FROM LabBlobChunks WHERE Sha256 = ? AND ChunkIndex >= ? ORDER BY ChunkIndex LIMIT 4;"

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
