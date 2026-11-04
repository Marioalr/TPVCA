package io.github.marioalr.tpvca.sync

/** Qué hace un equipo cuando la nube no aceptó una fila que subió (el upsert afectó 0 filas). */
enum class RejectedUpsertResolution { ApplyServerRow, ApplyServerTombstone, RetryLater }

/**
 * Las reglas del protocolo v2 que dan lo mismo en la PC y aquí (`Services/SyncRules.cs`). Vectores: `golden/lww.json` y
 * `golden/stamping.json`. Reciben las marcas en la forma de [ProtocolDate], así que se comparan como texto.
 */
object SyncRules {
    /** Al bajar: una fila remota se aplica si aquí no existe o si su marca es estrictamente más nueva. */
    fun remoteRowWins(localUpdatedAtUtc: String?, remoteUpdatedAtUtc: String): Boolean =
        localUpdatedAtUtc == null || remoteUpdatedAtUtc > localUpdatedAtUtc

    /** Al bajar: una lápida borra la fila local salvo que la local sea estrictamente más nueva. Sin fila no hay nada que borrar. */
    fun remoteTombstoneDeletes(localUpdatedAtUtc: String?, deletedAtUtc: String): Boolean =
        localUpdatedAtUtc != null && localUpdatedAtUtc <= deletedAtUtc

    /** Una fila que baja y aquí no existe, contra la lápida de aquí: si no es estrictamente más nueva que el borrado, no revive y la lápida sigue en la cola. Un empate lo gana el borrado. */
    fun localTombstoneBeatsRemoteRow(remoteUpdatedAtUtc: String, deletedAtUtc: String): Boolean = remoteUpdatedAtUtc <= deletedAtUtc

    /** La marca de un cambio local: `max(hora + desfase, guardada + 1 ms)`. La usa el motor de escritura (K5). */
    fun nextLocalStamp(nowUtc: String, storedUtc: String?, clockOffsetMs: Long): String {
        val candidate = ProtocolDate.parse(nowUtc)!!.plusNanos(clockOffsetMs * 1_000_000)
        val stored = storedUtc?.let(ProtocolDate::parse)
        return ProtocolDate.format(if (stored != null && !stored.isBefore(candidate)) stored.plusNanos(1_000_000) else candidate)
    }

    /** Después de un upsert rechazado (K5): la fila manda; si no hay fila, su lápida; si no hay ninguna, se reintenta. */
    fun afterRejectedUpsert(serverRowExists: Boolean, serverTombstoneExists: Boolean): RejectedUpsertResolution = when {
        serverRowExists -> RejectedUpsertResolution.ApplyServerRow
        serverTombstoneExists -> RejectedUpsertResolution.ApplyServerTombstone
        else -> RejectedUpsertResolution.RetryLater
    }

    /** Después de un upsert rechazado (K5): la fila de la nube se aplica si es igual o más nueva (así se deshace un empate). */
    fun serverRowAppliesAfterReject(localUpdatedAtUtc: String?, serverUpdatedAtUtc: String): Boolean =
        localUpdatedAtUtc == null || serverUpdatedAtUtc >= localUpdatedAtUtc
}
