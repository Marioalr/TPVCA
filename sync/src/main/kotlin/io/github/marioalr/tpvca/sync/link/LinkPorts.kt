package io.github.marioalr.tpvca.sync.link

import io.github.marioalr.tpvca.domain.devices.DeviceRole
import io.github.marioalr.tpvca.sync.engine.SyncStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * La credencial de este equipo: nunca viaja ni va en un respaldo. [token] es `null` después de borrarse por dos 401: el `DeviceId` y la
 * URL se quedan, porque la fila de `DeviceRegistry` sigue y con el token nuevo no hay que aprobarlo otra vez (§4.7). [toString] no muestra
 * ni el token ni la URL.
 *
 * [requestedRole] es el que eligió al vincularse y [approvedRole] el último que leyó en su fila, que es la autoridad. Va aquí y no solo en la
 * base porque sobrevive a borrarla: el bloqueo de la app lo necesita mientras espera el token nuevo.
 */
class DeviceCredential(
    val url: String,
    val token: String?,
    val clinicName: String,
    val deviceId: String,
    val requestedRole: DeviceRole,
    val clinicId: String? = null,
    val approvedRole: DeviceRole? = null,
) {
    /** El rol con el que trabaja este equipo: el que pidió solo sirve hasta que la PC lo aprueba. */
    val effectiveRole: DeviceRole get() = approvedRole ?: requestedRole

    fun copy(
        url: String = this.url,
        token: String? = this.token,
        deviceId: String = this.deviceId,
        clinicId: String? = this.clinicId,
        approvedRole: DeviceRole? = this.approvedRole,
    ) = DeviceCredential(url, token, clinicName, deviceId, requestedRole, clinicId, approvedRole)

    fun toJson(): String = buildJsonObject {
        put("url", url)
        put("token", token)
        put("clinicName", clinicName)
        put("deviceId", deviceId)
        put("role", requestedRole.name)
        put("clinicId", clinicId)
        put("approvedRole", approvedRole?.name)
    }.toString()

    override fun toString(): String = "DeviceCredential(deviceId=$deviceId, rol=$effectiveRole, conToken=${token != null})"

    companion object {
        /** `null` si no se puede leer. */
        fun fromJson(text: String): DeviceCredential? = try {
            val json = Json.parseToJsonElement(text) as? JsonObject
            val url = json?.text("url")
            val deviceId = json?.text("deviceId")
            val role = DeviceRole.fromName(json?.text("role"))
            if (json == null || url == null || deviceId == null || role == null) {
                null
            } else {
                DeviceCredential(
                    url,
                    json.text("token"),
                    json.text("clinicName").orEmpty(),
                    deviceId,
                    role,
                    json.text("clinicId"),
                    DeviceRole.fromName(json.text("approvedRole")),
                )
            }
        } catch (_: IllegalArgumentException) {
            null
        }

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}

/** Lo que hay guardado. [Unreadable]: existe pero no se puede abrir (el Keystore perdió la llave). */
sealed interface StoredCredential {
    data object Empty : StoredCredential

    data object Unreadable : StoredCredential

    data class Present(val credential: DeviceCredential) : StoredCredential
}

/** Donde vive la credencial. En el equipo, cifrada con el Keystore (`EncryptedCredentialVault` de `:data`). */
interface CredentialVault {
    fun load(): StoredCredential

    fun save(credential: DeviceCredential)

    fun clear()
}

/** La base local de este equipo. En el equipo, Room con SQLCipher (`EncryptedLocalDatabase` de `:data`). */
interface LocalDatabase {
    /** La base abierta; la crea si hace falta. */
    fun store(): SyncStore

    /** Cierra y borra la base, su llave y las imágenes del membrete. La siguiente [store] empieza con una base y una llave nuevas. */
    fun erase()
}

/** Lo que el equipo debe seguir diciendo después de borrarse: no es un secreto y sobrevive a la credencial. */
interface LinkNotices {
    /** La revocación que falta por leer, con su motivo; `null` si no hay. */
    fun revocation(): Revocation?

    fun saveRevocation(reason: String?)

    fun clearRevocation()
}

data class Revocation(val reason: String?)

/** Dónde queda el último número de cada serie con letra: fuera de la base. */
interface FolioNumbers : io.github.marioalr.tpvca.sync.engine.FolioLedger {
    /** Al quitar el equipo: el `DeviceId` nuevo trae otro código, así que no se puede repetir un folio. */
    fun clear()
}

/** Si el equipo tiene bloqueo de pantalla seguro (`KeyguardManager.isDeviceSecure`). */
fun interface DeviceLock {
    fun isSecure(): Boolean
}
