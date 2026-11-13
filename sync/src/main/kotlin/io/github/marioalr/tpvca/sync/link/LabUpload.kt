package io.github.marioalr.tpvca.sync.link

import io.github.marioalr.tpvca.domain.Clinic
import io.github.marioalr.tpvca.sync.engine.ConsumptionBrake
import io.github.marioalr.tpvca.sync.engine.StoreChanges
import io.github.marioalr.tpvca.sync.engine.SyncEngine
import io.github.marioalr.tpvca.sync.engine.SyncStore
import io.github.marioalr.tpvca.sync.hrana.HranaConnection
import io.github.marioalr.tpvca.sync.hrana.HranaException
import io.github.marioalr.tpvca.sync.hrana.HranaValue
import io.github.marioalr.tpvca.sync.hrana.ResultSet
import io.github.marioalr.tpvca.sync.hrana.Row
import io.github.marioalr.tpvca.sync.hrana.Statement
import io.github.marioalr.tpvca.sync.hrana.text
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Locale

/**
 * Los archivos que faltan por subir, **cifrados en el equipo**. Estar aquí es lo que dice que falta subirlo (lo que en la PC es `LabFileState.UploadedTo`): si la base se vacía por otra generación, lo pendiente no se olvida.
 * [ticket] es de `DataGate`: si hubo un borrado, nada de lo que se pida con un boleto viejo responde ni escribe.
 */
interface LabUploadFiles {
    fun ticket(): Long

    /** Sigue siendo el mismo equipo: entre trozo y trozo, un borrado detiene la subida. */
    fun alive(ticket: Long): Boolean

    fun hasPending(): Boolean

    fun pending(ticket: Long): List<String>

    /** Cuándo se guardó como pendiente (la hora del archivo), o `null` si ya no está. Uno recién guardado puede estar esperando a que se escriba su fila. */
    fun pendingSince(ticket: Long, sha256: String): Instant?

    /** El archivo en memoria, o `null` si está dañado: se queda donde está y no se sube. */
    fun read(ticket: Long, sha256: String): ByteArray?

    /** Ya no hace falta subirlo: se queda en la caché como cualquier otro. */
    fun settle(ticket: Long, sha256: String)

    /**
     * Lo mismo, solo si [stillOrphan] sigue diciendo que ningún resultado lo nombra: una importación que venga a la mitad no pierde su subida. Tampoco si está apartado: el resultado
     * que se está agregando aparta su archivo desde antes de guardarlo hasta que su fila queda escrita.
     */
    fun settleOrphan(ticket: Long, sha256: String, stillOrphan: () -> Boolean)
}

/** Lo que hizo una vuelta. [failed]: se cortó la red o la nube respondió con error, y conviene reintentar. [notice]: por qué se detuvo algo (el tope, el freno), lo que no se arregla reintentando. */
data class LabUploadReport(val uploaded: Int = 0, val waitingForCap: Int = 0, val damaged: Int = 0, val notice: String? = null, val failed: Boolean = false)

/** El freno de bytes por día de la subida. Sobrevive a otra generación, como los demás del freno ([ConsumptionBrake.META_PREFIX]). */
class LabUploadBytes(private val store: SyncStore, private val clock: Clock) {
    private fun dayKey() = Clinic.today(clock.instant()).toString()

    /** Lo subido hoy, en bytes de base64. */
    fun today(): Long = if (store.meta(DAY) == dayKey()) store.meta(BYTES)?.toLongOrNull() ?: 0L else 0L

    fun add(bytes: Long) {
        store.apply(StoreChanges(meta = mapOf(DAY to dayKey(), BYTES to (today() + bytes).toString())))
    }

    private companion object {
        const val DAY = "${ConsumptionBrake.META_PREFIX}LabUpDay"
        const val BYTES = "${ConsumptionBrake.META_PREFIX}LabUpBytes"
    }
}

/**
 * Sube los archivos que este equipo agregó (`UploadLabFileAsync`), con el SQL de `lab-files.json`: `state` y `existingChunks` en una petición; sin fila, el tope (`usage` y `cloudSpace`) y `register`; cada trozo que falta con `insertChunk`, uno por
 * petición, y el último en un `batch` con BEGIN/COMMIT junto con `close`. Un corte sigue después con los trozos que faltan. Uno que la nube desalojó a propósito no se vuelve a subir.
 *
 * **Nunca escribe nada en claro:** el archivo solo está en memoria mientras sube. Bloquea: corre fuera del hilo principal y sin el candado de la sincronización, para que un archivo grande no la detenga.
 */
class LabFileUploader(
    private val connection: HranaConnection,
    private val store: SyncStore,
    private val files: LabUploadFiles,
    private val deviceId: String,
    private val clock: Clock = Clock.systemUTC(),
    /** Alrededor de lo que toca la base: en la app, `DataGate`. */
    private val guard: (() -> Any?) -> Any? = { it() },
    private val maxBytesPerDay: Long = LabFileRules.ANDROID_UP_BYTES_PER_DAY,
    private val cloudCap: Long = LabFileRules.CLOUD_CAP_BYTES,
    private val cloudSpaceStop: Long = LabFileRules.CLOUD_SPACE_STOP_BYTES,
) {
    private class Usage(var bytes: Long, val space: Long?)

    private class File(val sha256: String, val mimeType: String, val date: String, val created: String)

    private var usage: Usage? = null
    private var rowsRead = 0L
    private var rowsWritten = 0L
    private var uploaded = 0
    private var waiting = 0
    private var damaged = 0
    private var notice: String? = null

    @Suppress("UNCHECKED_CAST")
    private fun <T> guarded(block: () -> T): T = guard { block() } as T

    private fun bytes() = LabUploadBytes(store, clock)

    /** Una vuelta. No lanza por la red: lo dice [LabUploadReport.failed], y en la siguiente sigue donde se quedó. */
    fun run(): LabUploadReport {
        val ticket = files.ticket()
        var failed = false
        try {
            for (file in work(ticket)) {
                if (!files.alive(ticket)) break
                if (brakeReached()) break
                upload(file, ticket)
            }
        } catch (e: HranaException) {
            failed = true
            notice = "Los archivos de laboratorio se siguen después: ${e.message}"
        }
        if (rowsRead > 0 || rowsWritten > 0) guarded { ConsumptionBrake(store, clock).recordUsage(rowsRead, rowsWritten) }
        return LabUploadReport(uploaded, waiting, damaged, notice, failed)
    }

    /**
     * Lo que hay que subir, solo con lo de aquí (`LoadLabWorkAsync`): lo más reciente primero, y lo que ya ningún resultado nombra se suelta sin gastar la nube. Solo si lleva más de
     * [ORPHAN_MARGIN] guardado: el archivo se guarda antes que su fila, y uno recién guardado sin fila es un resultado que se está agregando, no un huérfano.
     */
    private fun work(ticket: Long): List<File> {
        val pending = files.pending(ticket)
        if (pending.isEmpty()) return emptyList()
        val rows = guarded { rowsNaming(pending) }.groupBy { it["ContentSha256"] as String }
        val settledBefore = clock.instant().minus(ORPHAN_MARGIN)
        for (sha in pending) {
            if (sha in rows) continue
            val since = files.pendingSince(ticket, sha) ?: continue
            if (since.isAfter(settledBefore)) continue
            files.settleOrphan(ticket, sha) { guarded { rowsNaming(listOf(sha)) }.isEmpty() }
        }
        return rows.filterKeys { it in pending }.map { (sha, named) ->
            File(
                sha,
                named.first()["MimeType"] as String? ?: LabFileRules.PDF_MIME_TYPE,
                named.minOf { it["ResultDate"] as String? ?: "" },
                named.maxOf { it["CreatedAt"] as String? ?: "" },
            )
        }.sortedWith(compareByDescending<File> { it.date }.thenByDescending { it.created })
    }

    private fun rowsNaming(shas: List<String>) = store.select("LabResults", listOf("ContentSha256", "MimeType", "ResultDate", "CreatedAt"), "ContentSha256", shas)

    private fun upload(file: File, ticket: Long) {
        val sha = file.sha256
        val content = files.read(ticket, sha)
        if (content == null) {
            damaged++
            return
        }
        val args = listOf<HranaValue>(HranaValue.Text(sha))
        val before = connection.query(listOf(Statement(LabFileRules.STATE_SQL, args), Statement(LabFileRules.EXISTING_CHUNKS_SQL, args)))
        rowsRead += 1 + before[1].rows.size
        val state = before[0].rows.firstOrNull()
        if (state != null && (filled(state, "EvictedAtUtc") || filled(state, "CompletedAtUtc"))) {
            // Ya está (lo subió otro equipo) o se desalojó a propósito: no se gasta la nube otra vez
            files.settle(ticket, sha)
            return
        }

        val size = content.size.toLong()
        val chunkCount = LabFileRules.chunkCount(size)
        if (state == null) {
            val measured = usage ?: measure().also { usage = it }
            if (!fits(measured, size)) {
                waiting++
                notice = notice ?: capNotice(measured, size)
                return
            }
            connection.query(
                listOf(
                    Statement(
                        LabFileRules.REGISTER_SQL,
                        listOf(HranaValue.Text(sha), HranaValue.Integer(size), HranaValue.Integer(LabFileRules.CHUNK_SIZE.toLong()), HranaValue.Integer(chunkCount.toLong()), HranaValue.Text(file.mimeType), HranaValue.Text(deviceId)),
                    ),
                ),
            )
            rowsRead += 1
            rowsWritten += 2
            measured.bytes += size
        } else if (state.text("SizeBytes")?.toLongOrNull() != size || state.text("ChunkCount")?.toIntOrNull() != chunkCount) {
            // La nube lo registró con otro tamaño: no se le agrega nada
            return
        }

        val existing = before[1].rows.mapNotNull { it.text("ChunkIndex")?.toIntOrNull() }.toSet()
        val missing = (0 until chunkCount).filterNot { it in existing }
        val close = Statement(LabFileRules.CLOSE_SQL, listOf(HranaValue.Text(sha), HranaValue.Text(sha)))
        var closed = 0L
        if (missing.isEmpty()) {
            closed = connection.query(listOf(close)).single().affectedRows
            rowsRead += chunkCount + 1
            rowsWritten += closed
        }
        missing.forEachIndexed { position, index ->
            if (!files.alive(ticket)) return
            val from = index * LabFileRules.CHUNK_SIZE
            val piece = content.copyOfRange(from, minOf(from + LabFileRules.CHUNK_SIZE, content.size))
            val insert = Statement(LabFileRules.INSERT_CHUNK_SQL, listOf(HranaValue.Text(sha), HranaValue.Integer(index.toLong()), HranaValue.Blob(piece)))
            if (position == missing.lastIndex) {
                closed = connection.snapshot(emptyList(), listOf(insert, close)).statements[1].affectedRows
                rowsRead += chunkCount + 2
                rowsWritten += 2 + closed
            } else {
                connection.query(listOf(insert))
                rowsRead += 1
                rowsWritten += 2
            }
            guarded { bytes().add(LabFileRules.travelBytes(piece.size.toLong())) }
            if (position < missing.lastIndex && brakeReached()) return
        }

        if (closed == 0L) {
            val after = connection.query(listOf(Statement(LabFileRules.STATE_SQL, args))).single().rows.firstOrNull()
            rowsRead += 1
            // Le falta algo en la nube: sigue en la siguiente vuelta
            if (after == null || !filled(after, "CompletedAtUtc")) return
        }
        files.settle(ticket, sha)
        uploaded++
    }

    private fun filled(row: Row, column: String) = !row.text(column).isNullOrEmpty()

    private fun measure(): Usage {
        val results = connection.query(listOf(Statement(LabFileRules.USAGE_SQL)) + LabFileRules.CLOUD_SPACE_SQL.map { Statement(it) })
        val files = results[0].rows.firstOrNull()?.text("Files")?.toLongOrNull() ?: 0L
        val bytes = results[0].rows.firstOrNull()?.text("Bytes")?.toLongOrNull() ?: 0L
        fun scalar(result: ResultSet) = result.rows.firstOrNull()?.values?.firstOrNull()?.text?.toLongOrNull() ?: 0L
        val pageSize = scalar(results[3])
        rowsRead += files + 3
        return Usage(bytes, if (pageSize > 0) (scalar(results[1]) - scalar(results[2])) * pageSize else null)
    }

    private fun fits(usage: Usage, size: Long) = usage.bytes + size <= cloudCap && (usage.space == null || usage.space + size <= cloudSpaceStop)

    private fun capNotice(usage: Usage, size: Long): String =
        if ((usage.space ?: 0L) > cloudSpaceStop - size) {
            "La nube está casi llena (${megabytes(usage.space ?: 0L)} de 5 GB): los resultados nuevos se quedan en este equipo."
        } else {
            "La nube de laboratorio llegó a su tope (${megabytes(cloudCap)}): los resultados nuevos se quedan en este equipo. La PC principal libera espacio si tiene un respaldo externo (Configuración › Respaldos y datos)."
        }

    private fun brakeReached(): Boolean {
        if (guarded { bytes().today() } < maxBytesPerDay) return false
        notice = notice ?: "Se llegó al tope de subida de resultados de laboratorio de hoy (${megabytes(maxBytesPerDay)}); sigue mañana."
        return true
    }

    companion object {
        /** Lo que un pendiente sin fila espera antes de contarse como huérfano: de sobra para escribir la fila de un resultado que se está agregando. */
        val ORPHAN_MARGIN: Duration = Duration.ofMinutes(10)

        /** `LabFileImporter.Megabytes`. */
        fun megabytes(bytes: Long): String = String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0)

        /** `PauseReason` de la PC: con el freno, la cuota, el token rechazado, solo lectura o revocado, ninguna vuelta toca la nube. */
        fun paused(store: SyncStore, clock: Clock): Boolean {
            if (ConsumptionBrake(store, clock).isTripped) return true
            val retryAt = store.meta(SyncEngine.QUOTA_RETRY_AT)?.let(Instant::parse)
            if (retryAt != null && clock.instant().isBefore(retryAt)) return true
            return listOf(SyncEngine.AUTH_FIRST_401, SyncEngine.AUTH_MALFORMED, SyncEngine.READ_ONLY, SyncEngine.REVOKED_REASON).any { store.meta(it) != null }
        }
    }
}
