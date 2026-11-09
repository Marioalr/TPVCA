package io.github.marioalr.tpvca.documents

import io.github.marioalr.tpvca.data.security.DataGate
import io.github.marioalr.tpvca.data.security.DataGateClosedException
import io.github.marioalr.tpvca.data.security.LabFileCache
import io.github.marioalr.tpvca.sync.link.LabFileRules
import io.github.marioalr.tpvca.sync.link.LabUploadFiles

/**
 * Los archivos de laboratorio de este equipo para la tablet (K7): guardar el que el médico acaba de agregar como pendiente de subir, saber si un resultado ya está aquí, y lo que lee el
 * subidor ([LabUploadFiles]). Todo pasa por el [DataGate]: si hubo un borrado, nada de lo que se pida con un boleto viejo responde ni escribe. Bloquea: corre fuera del hilo principal.
 */
class LabFileStore(private val cache: LabFileCache, private val gate: DataGate = DataGate()) : LabUploadFiles {
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

    override fun settleOrphan(ticket: Long, sha256: String, stillOrphan: () -> Boolean) = gate.write(ticket) { if (stillOrphan()) cache.settle(sha256) }

    /** Un resultado nuevo: el archivo se guarda antes que su fila, y queda pendiente de subir. Regresa su SHA-256. */
    fun save(ticket: Long, content: ByteArray): String {
        val sha = LabFileRules.sha256(content)
        gate.write(ticket) { cache.writePending(sha, content) }
        return sha
    }

    /** Está en este equipo (bajado o agregado aquí). */
    fun isLocal(sha256: String): Boolean = runCatching { gate.read { cache.read(sha256) != null } }.getOrDefault(false)

    /** Agregado aquí y todavía sin subir. */
    fun isPending(sha256: String): Boolean = runCatching { gate.read { cache.isPending(sha256) } }.getOrDefault(false)
}
