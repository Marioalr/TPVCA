package io.github.marioalr.tpvca.sync.link

import io.github.marioalr.tpvca.domain.devices.DeviceRole

/** Por qué no se vinculó o qué se avisa al volver a la pantalla de vincular. Ninguno repite lo pegado. */
sealed interface LinkProblem {
    /** Plan §4.8: sin vinculación si el equipo no tiene bloqueo de pantalla seguro. */
    data object NoSecureLock : LinkProblem

    data class BadUrl(val message: String) : LinkProblem

    data object EmptyToken : LinkProblem

    /** Trae caracteres que un token no lleva. */
    data object BadToken : LinkProblem

    /** Lo pegado o escaneado lleva a otro host: el token no sale hacia allá hasta confirmar [host] en pantalla. */
    data class HostChange(val host: String) : LinkProblem

    /** Esa nube es de otro consultorio: no se guardó nada. */
    data object OtherClinic : LinkProblem

    /** No se pudo comprobar de quién es esa nube ([message]): no se guardó nada. */
    data class CloudUnverified(val message: String) : LinkProblem

    /** La credencial ya no se puede abrir (el Keystore perdió la llave): se borró todo y hay que vincular otra vez. */
    data object CredentialLost : LinkProblem
}

/** En qué parte de la vinculación está este equipo. Ningún estado lleva el token ni la URL. */
sealed interface LinkState {
    data class NotLinked(val problem: LinkProblem? = null) : LinkState

    /**
     * Escribió su latido y espera su fila de `DeviceRegistry`, sin bajar nada. [problem] es lo que impidió revisar esta vez (sin red).
     * [newIdentity]: otra instalación usaba su `DeviceId` y este equipo tomó uno nuevo, así que la PC debe aprobarlo otra vez.
     */
    data class WaitingApproval(
        val suffix: String,
        val role: DeviceRole,
        val clinicName: String,
        val problem: String? = null,
        val newIdentity: Boolean = false,
        val braked: Boolean = false,
    ) : LinkState

    /** La PC lo aprobó y la primera descarga no ha terminado. [notice]: por qué se detuvo; sigue donde iba. */
    data class Downloading(val role: DeviceRole, val clinicName: String, val notice: String? = null, val braked: Boolean = false) : LinkState

    /** Vinculado. [role] es el de su fila de `DeviceRegistry`, aunque haya pedido el otro. [notice]: lo último que hay que avisar (sin red, cuota, solo lectura); los datos siguen aquí. [braked]: se ofrece *Reanudar*. */
    data class Linked(val role: DeviceRole, val code: String?, val clinicName: String, val notice: String? = null, val braked: Boolean = false) : LinkState

    /**
     * Turso rechazó el token: 401 (se anuló) o 400 con "JWT" ([malformed], mal copiado). [pending]: los cambios sin subir, que se conservan.
     * [erased]: dos 401 con 10 min de diferencia y nada pendiente: se borró la base y, con el token nuevo, vuelve a bajar todo sin aprobar.
     * [problem]: lo que se acaba de pegar no sirve. [manual]: no lo rechazó Turso; lo pidió el médico para salir de una nube que no sirve.
     */
    data class NeedsToken(
        val malformed: Boolean,
        val erased: Boolean,
        val pending: Int,
        val problem: LinkProblem? = null,
        val manual: Boolean = false,
    ) : LinkState

    /** La fila dice un rol que no es de un equipo Android (una PC, o uno que esta versión no conoce). No se bajó ni se borró nada: lo corrige la PC. */
    data class WrongRole(val registered: String?) : LinkState

    /**
     * La nube no se puede usar todavía u otra vez (sin esquema, sin verificar, otra versión, otro consultorio). No se bajó nada. [canReplace]:
     * se ofrece pegar otra URL y token; [canForget]: se ofrece desvincular, con confirmación. Ninguno en lo que se arregla solo.
     */
    data class CloudProblem(val message: String, val canReplace: Boolean = false, val canForget: Boolean = false) : LinkState

    /**
     * No se pudo leer lo guardado en este equipo (el Keystore o el disco fallaron). Nada se borró y puede ser pasajero: se vuelve a intentar. Sin
     * saber qué hay guardado, la app queda bloqueada y nada se puede borrar.
     */
    data object LocalError : LinkState

    /**
     * La PC lo quitó, lo marcó como perdido o lo rechazó. [erased]: ya se borraron la base, la credencial y los borradores. Sin borrar solo
     * si es `quitado` con [pending] cambios sin subir: los sube K5, antes de borrarse.
     */
    data class Revoked(val reason: String?, val erased: Boolean, val pending: Int = 0) : LinkState
}
