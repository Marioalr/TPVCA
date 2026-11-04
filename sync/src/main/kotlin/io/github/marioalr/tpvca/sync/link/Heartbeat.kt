package io.github.marioalr.tpvca.sync.link

import io.github.marioalr.tpvca.domain.devices.DeviceRole
import io.github.marioalr.tpvca.sync.Statements
import io.github.marioalr.tpvca.sync.SyncContract
import io.github.marioalr.tpvca.sync.hrana.HranaValue
import io.github.marioalr.tpvca.sync.hrana.ResultSet
import io.github.marioalr.tpvca.sync.hrana.Statement
import io.github.marioalr.tpvca.sync.hrana.text

/** Lo que dice el latido de este equipo. [role] es el que pide y, ya aprobado, el de su fila de `DeviceRegistry`, que es la autoridad. */
data class HeartbeatInfo(
    val deviceId: String,
    val deviceName: String,
    val role: DeviceRole,
    val appVersion: String,
    val pendingCount: Int,
    val oldestPendingUtc: String?,
    val instanceNonce: String,
)

/** El latido (regla «Latido» de `schema.json`): en una petición, primero lee el `InstanceNonce` de su fila y luego la escribe. */
object Heartbeat {
    const val PLATFORM = "Android"

    /**
     * 0 hasta K8. La PC ofrece consultas a quien trae `HandoffProtocol >= 1` y, si viene vacío, a quien trae `AppVersion >= 1.17.0`: el
     * 0 explícito no deja que esta app reciba una consulta que todavía no sabe tomar.
     */
    const val HANDOFF_PROTOCOL = 0

    fun statements(info: HeartbeatInfo): List<Statement> = listOf(
        Statement(Statements.HEARTBEAT_READ_NONCE, listOf(HranaValue.Text(info.deviceId))),
        Statement(
            Statements.HEARTBEAT_WRITE,
            listOf(
                HranaValue.Text(info.deviceId),
                HranaValue.Text(info.deviceName),
                HranaValue.Text(info.role.name),
                HranaValue.Text(info.appVersion),
                HranaValue.Text(PLATFORM),
                HranaValue.Integer(SyncContract.CLIENT_PROTOCOL.toLong()),
                HranaValue.Integer(HANDOFF_PROTOCOL.toLong()),
                HranaValue.Integer(SyncContract.REVISION.toLong()),
                HranaValue.Integer(info.pendingCount.toLong()),
                info.oldestPendingUtc?.let(HranaValue::Text) ?: HranaValue.Null,
                HranaValue.Text(info.instanceNonce),
            ),
        ),
    )

    /**
     * Si este proceso ya había escrito su nonce y la fila trae otro, otra instalación usa su `DeviceId`. El primer latido de cada arranque
     * no revisa: su fila trae el del arranque anterior.
     */
    fun otherInstallation(before: ResultSet, alreadyWritten: Boolean, nonce: String): Boolean {
        val previous = before.rows.firstOrNull()?.text("InstanceNonce")
        return alreadyWritten && !previous.isNullOrEmpty() && previous != nonce
    }

    /** Los últimos 6 caracteres del `DeviceId` en mayúsculas: lo que se compara con la pantalla de la PC (`LinkedDevice.DeviceIdSuffix`). */
    fun suffix(deviceId: String): String = deviceId.takeLast(6).uppercase(java.util.Locale.ROOT)
}
