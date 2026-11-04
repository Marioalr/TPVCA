package io.github.marioalr.tpvca.sync.rules

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * Identificadores del protocolo (`Services/SyncIds.cs`, `golden/uuid5.json`): un SyncId viaja y entra a un UUIDv5 en minúsculas con guiones. Los
 * deterministas dejan que dos equipos que hacen lo mismo (cancelar un ticket, cerrar un lote) escriban la misma fila.
 */
object SyncIds {
    val DNS: UUID = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8")
    val URL: UUID = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8")

    /** `uuid5(url, "https://github.com/Marioalr/TPVC/sync")`. No cambia nunca. */
    val TPVC: UUID = uuid5(URL, "https://github.com/Marioalr/TPVC/sync")

    const val EMPTY: String = "00000000-0000-0000-0000-000000000000"

    fun uuid5(namespace: UUID, name: String): UUID {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(ByteBuffer.allocate(16).putLong(namespace.mostSignificantBits).putLong(namespace.leastSignificantBits).array())
        val hash = digest.digest(name.toByteArray(Charsets.UTF_8))
        hash[6] = ((hash[6].toInt() and 0x0F) or 0x50).toByte()
        hash[8] = ((hash[8].toInt() and 0x3F) or 0x80).toByte()
        val buffer = ByteBuffer.wrap(hash, 0, 16)
        return UUID(buffer.getLong(), buffer.getLong())
    }

    /** `prefix` + los SyncId en minúsculas unidos con `:`. */
    fun derived(prefix: String, vararg syncIds: String): String =
        uuid5(TPVC, prefix + syncIds.joinToString(":") { it.lowercase(Locale.ROOT) }).toString()

    /** Un `SyncId` nuevo: al azar, en minúsculas con guiones. */
    fun random(): String = UUID.randomUUID().toString()

    /** El renglón de una hoja que llegó sin `RowId`: `sheetrow:<paciente>:<columna>:<posición desde 0>:<nombre tal cual>`. */
    fun legacyRowId(patientSyncId: String, column: String, position: Int, name: String?): String =
        uuid5(TPVC, "sheetrow:${patientSyncId.lowercase(Locale.ROOT)}:$column:$position:${name.orEmpty()}").toString()
}
