package io.github.marioalr.tpvca.documents

import io.github.marioalr.tpvca.data.security.DataGate
import io.github.marioalr.tpvca.data.security.DataGateClosedException
import io.github.marioalr.tpvca.data.security.LabFileCache
import io.github.marioalr.tpvca.sync.link.LabFileRules
import io.github.marioalr.tpvca.sync.link.LabUploadFiles
import java.time.Instant
import java.util.Locale

/**
 * Los archivos de laboratorio de este equipo para la tablet (K7): guardar el que el médico acaba de agregar como pendiente de subir, saber si un resultado ya está aquí, y lo que lee el
 * subidor ([LabUploadFiles]). Todo pasa por el [DataGate]: si hubo un borrado, nada de lo que se pida con un boleto viejo responde ni escribe. Bloquea: corre fuera del hilo principal.
 *
 * **Un resultado que se está agregando aparta su archivo** ([save]) desde antes de guardarlo hasta que su fila queda escrita: mientras, la subida no lo suelta como huérfano
 * ([settleOrphan]). Además la subida espera `LabFileUploader.ORPHAN_MARGIN` antes de contar a uno como huérfano.
 */
class LabFileStore(private val cache: LabFileCache, private val gate: DataGate = DataGate()) : LabUploadFiles {
    /** Los archivos apartados, con cuántos resultados los apartan (el mismo archivo puede agregarse dos veces a la vez). */
    private val claims = HashMap<String, Int>()

    override fun ticket(): Long = gate.ticket()

    override fun alive(ticket: Long): Boolean = try {
        gate.write(ticket) { true }
    } catch (_: DataGateClosedException) {
        false
    }

    override fun hasPending(): Boolean = runCatching { gate.read { cache.pending().isNotEmpty() } }.getOrDefault(false)

    override fun pending(ticket: Long): List<String> = gate.write(ticket) { cache.pending() }

    override fun read(ticket: Long, sha256: String): ByteArray? = gate.write(ticket) { runCatching { cache.read(sha256) }.getOrNull() }

    override fun settle(ticket: Long, sha256: String) = gate.write(ticket) { cache.settle(sha256) }

    override fun pendingSince(ticket: Long, sha256: String): Instant? = gate.write(ticket) { cache.pendingSince(sha256)?.let(Instant::ofEpochMilli) }

    /** Lo apartado no se suelta. Se revisa y se suelta con el apartado tomado: un [save] no puede apartarlo a la mitad. */
    override fun settleOrphan(ticket: Long, sha256: String, stillOrphan: () -> Boolean) = gate.write(ticket) {
        synchronized(claims) { if (key(sha256) !in claims && stillOrphan()) cache.settle(sha256) }
    }

    /**
     * Un resultado nuevo: aparta el archivo, lo guarda pendiente de subir (antes que su fila) y llama a [writeRow] con su SHA-256 para escribir la fila. El apartado se suelta al
     * terminar [writeRow], con la fila ya escrita (o sin ella si falló: entonces es huérfano, y se suelta pasado el margen). Regresa lo que regresó [writeRow].
     */
    fun <T> save(ticket: Long, content: ByteArray, writeRow: (String) -> T): T {
        val sha = LabFileRules.sha256(content)
        val key = key(sha)
        synchronized(claims) { claims[key] = (claims[key] ?: 0) + 1 }
        try {
            gate.write(ticket) { cache.writePending(sha, content) }
            return writeRow(sha)
        } finally {
            synchronized(claims) {
                val left = (claims[key] ?: 1) - 1
                if (left > 0) claims[key] = left else claims.remove(key)
            }
        }
    }

    private fun key(sha256: String) = sha256.lowercase(Locale.ROOT)

    /** Está en este equipo (bajado o agregado aquí), como `File.Exists` de la PC: sin descifrarlo. Si estuviera dañado, al abrirlo se vuelve a bajar. */
    fun isLocal(sha256: String): Boolean = runCatching { gate.read { cache.contains(sha256) } }.getOrDefault(false)

    /** Agregado aquí y todavía sin subir. */
    fun isPending(sha256: String): Boolean = runCatching { gate.read { cache.isPending(sha256) } }.getOrDefault(false)
}
