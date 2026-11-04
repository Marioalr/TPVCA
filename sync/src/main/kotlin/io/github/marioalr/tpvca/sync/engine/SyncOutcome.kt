package io.github.marioalr.tpvca.sync.engine

import java.time.Instant

/** Cómo terminó una sincronización. [message] es el texto para el médico; `null` si no hay nada que avisar. */
sealed class SyncOutcome(open val message: String?) {
    data class Completed(
        val fullDownload: Boolean,
        val applied: Int,
        val rowsRead: Long,
        /** La nube y la app no tienen el mismo manifiesto: la app no escribe nada (§7.3). */
        val readOnly: ManifestDiff?,
        val clockWarning: String? = null,
        /** Las filas que la nube sí cambió con la subida de esta sincronización. */
        val pushed: Int = 0,
    ) : SyncOutcome(readOnly?.let { ManifestDiff.MESSAGE } ?: clockWarning)

    /** Falta `_tpvc_meta` o su huella: la PC todavía no crea el esquema. Esta app nunca corre DDL. */
    data object SchemaMissing : SyncOutcome("Sincronice primero la PC: la nube todavía no tiene el esquema.")

    /** La PC todavía no termina su «Sincronización completa» (faltan `clinicId`, `fullUploadAtUtc` o `generationReady`). */
    data class CloudNotReady(val missing: List<String>) :
        SyncOutcome("La nube todavía no está lista: haga la «Sincronización completa» en la PC.")

    /** `minClientProtocol` es mayor que el de esta app. */
    data class ClientOutdated(val minimum: Int) : SyncOutcome(
        "Actualice la app: la nube ya usa una versión más nueva de la sincronización (protocolo $minimum).",
    )

    /** La nube es de otro consultorio. */
    data class OtherCloud(val cloudClinicId: String, val localClinicId: String) :
        SyncOutcome("Esta nube es de otro consultorio: no se bajó nada.")

    /** Sin fila en `DeviceRegistry`: la PC todavía no aprueba este equipo. */
    data object NotApproved : SyncOutcome("Este equipo espera que la PC lo apruebe en Equipos vinculados.")

    /**
     * Su fila dice un rol que no es de un equipo Android (una PC, o uno que esta versión no conoce). No baja nada y no borra nada: lo corrige
     * la PC. Cualquiera de los dos roles de Android sirve: `DeviceRegistry` manda, aunque este equipo haya pedido el otro.
     */
    data class WrongRole(val role: String?) :
        SyncOutcome("La PC registró este equipo con otro rol${role?.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty()}. Revíselo en Equipos vinculados.")

    /**
     * Con `RevokedAtUtc`, o con código X. [wiped]: se borró la base. Sin borrar solo si hay cambios sin subir que no deben perderse.
     * [reason] es `quitado`, `perdido` o `rechazado`.
     */
    data class Revoked(val reason: String?, val wiped: Boolean) : SyncOutcome(
        when (reason) {
            REASON_REJECTED -> "La PC rechazó este equipo."
            REASON_LOST -> "Este equipo se marcó como perdido en la PC."
            else -> "Este equipo se quitó de los equipos vinculados."
        },
    ) {
        companion object {
            const val REASON_REMOVED = "quitado"
            const val REASON_LOST = "perdido"
            const val REASON_REJECTED = "rechazado"
        }
    }

    /** Otra PC reemplaza la nube con un respaldo: no se baja nada hasta `generationReady`. */
    data object GenerationPending :
        SyncOutcome("Otra computadora está reemplazando la nube con un respaldo. La sincronización sigue sola cuando termine.")

    /** La generación cambió a media bajada: no se avanzó ningún cursor y la siguiente sincronización lo resuelve. */
    data object GenerationChanged :
        SyncOutcome("La nube cambió mientras se sincronizaba: otra computadora la reemplazó con un respaldo. La siguiente sincronización lo resuelve.")

    /** `BLOCKED`: sin sincronización automática hasta [retryAt]; lo que pide el médico sigue intentando. */
    data class QuotaPaused(val retryAt: Instant) : SyncOutcome(io.github.marioalr.tpvca.sync.hrana.HranaException.QUOTA_MESSAGE)

    /** El freno de consumo está activo (se quita con *Reanudar*). */
    data object BrakePaused : SyncOutcome("Sincronización pausada: consumo anormal.")

    /** HTTP 401 (§4.7). [wiped]: dos 401 con 10 min de diferencia y nada pendiente. */
    data class TokenRejected(val wiped: Boolean) : SyncOutcome(io.github.marioalr.tpvca.sync.hrana.HranaException.TOKEN_REJECTED_MESSAGE)

    /** HTTP 400 con "JWT": un token mal copiado. No cuenta para borrarse. */
    data object TokenMalformed : SyncOutcome(io.github.marioalr.tpvca.sync.hrana.HranaException.TOKEN_MALFORMED_MESSAGE)

    /** La base está archivada. */
    data object Archived : SyncOutcome(io.github.marioalr.tpvca.sync.hrana.HranaException.ARCHIVED_MESSAGE)

    /** Cualquier otro error (sin red, SQL, HTTP): se reintenta después y lo aplicado ya quedó con su cursor. */
    data class Failed(val code: String?, override val message: String) : SyncOutcome(message)
}
