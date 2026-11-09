package io.github.marioalr.tpvca.link

import io.github.marioalr.tpvca.sync.hrana.ResultSet
import io.github.marioalr.tpvca.sync.hrana.Statement
import io.github.marioalr.tpvca.sync.link.HandoffCheck
import io.github.marioalr.tpvca.sync.link.HandoffService
import kotlinx.coroutines.flow.StateFlow

/**
 * Lo que la consulta entre equipos (K8) necesita de la vinculación: la nube de ahora, el latido para «Enviar a…», una sincronización que espera a terminar, lo que trajo cada revisión
 * y el contexto que la cadencia necesita. En la app lo da `LinkController`; en las pruebas, una nube simulada.
 */
interface HandoffLink {
    /** La tabla `ConsultationHandoffs` con la nube de ahora; `null` sin vinculación. Sus llamadas bloquean: fuera del hilo principal. */
    fun service(): HandoffService?

    /** El latido de este equipo, para leer la lista de equipos con el suyo al día; `null` sin vinculación. */
    fun heartbeat(): List<Statement>?

    /** Después de escribir el latido con la lista: lo que había en la fila (otra instalación con este `DeviceId`) y cuántos compañeros hay. */
    fun afterHeartbeat(before: ResultSet, peerCount: Int)

    /** Algo de la consulta entre equipos llegó a la nube: la revisión espaciada por la red vuelve a su paso. */
    fun reachedCloud()

    /** Sincroniza ahora y espera (`SyncAsync` de la PC): `null` si terminó bien; si no, por qué. Sube lo pendiente antes de enviar y baja lo último antes de tomar. */
    suspend fun syncNow(): String?

    /** Cada revisión de la cadencia: lo que trajo o por qué no llegó a la nube. */
    val checks: StateFlow<HandoffCheck?>

    /** Lo que la cadencia necesita de la pantalla: si el médico está en Pacientes y el envío propio pendiente. */
    fun setContext(inPatients: Boolean, outgoingId: String?)
}
