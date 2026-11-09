package io.github.marioalr.tpvca.sync.rules

import io.github.marioalr.tpvca.sync.ProtocolDate
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.TreeMap
import java.util.UUID

/**
 * Las marcas por campo de `Patients.FieldStampsJson` (`Services/Rules/PatientFieldStamps.cs`, `golden/field-stamps.json`): un objeto plano {llave: marca}. `"<columna>"`, `"<hoja>/<RowId>"`,
 * `"<hoja>/<RowId>/quitado"`, `"#piso"` y `"UpdatedAtUtc"`; cualquier otra llave es de una versión más nueva y se conserva. Las marcas de 27 caracteres se comparan como texto.
 */
class PatientFieldStamps private constructor(private val entries: TreeMap<String, String>) {
    constructor() : this(TreeMap())

    val all: Map<String, String> get() = entries

    operator fun get(key: String): String? = entries[key]

    operator fun set(key: String, value: String?) {
        if (value == null) entries.remove(key) else entries[key] = value
    }

    fun clone() = PatientFieldStamps(TreeMap(entries))

    /** El texto canónico que se guarda; `""` sin ninguna marca. */
    fun toJson(): String = if (entries.isEmpty()) "" else CanonicalJson.write(JsonObject(entries.mapValues { JsonPrimitive(it.value) }))

    /** Una escritura sin marcas: su `UpdatedAtUtc` es mayor que el de sus marcas, o no las tiene. */
    fun isStampless(rowUpdatedAtUtc: String): Boolean {
        val stamp = parseStamp(this[ROW_STAMP_KEY]) ?: return true
        return rowUpdatedAtUtc > stamp
    }

    /** La marca de una llave: nunca más que el `UpdatedAtUtc` de su fila. */
    fun effective(key: String, rowUpdatedAtUtc: String): String? {
        val value = this[key] ?: return null
        val stamp = parseStamp(value) ?: return value
        return if (stamp > rowUpdatedAtUtc) rowUpdatedAtUtc else value
    }

    /** La marca más nueva de cualquier llave salvo la de la fila. El piso cuenta. */
    fun newest(): String? = entries.filterKeys { it != ROW_STAMP_KEY }.values.mapNotNull(::parseStamp).maxOrNull()

    /** El estado normalizado de una versión: marcas nunca mayores que la fila, el piso, y sin las llaves conocidas que no lo rebasan ni la de la fila. */
    fun state(rowUpdatedAtUtc: String, fieldColumns: Collection<String>, stampless: Boolean = isStampless(rowUpdatedAtUtc)): PatientFieldStamps {
        val state = PatientFieldStamps()
        for (key in entries.keys) {
            if (key != ROW_STAMP_KEY) state[key] = effective(key, rowUpdatedAtUtc)
        }
        state[FLOOR_KEY] = max(state[FLOOR_KEY], if (stampless) rowUpdatedAtUtc else null)
        state.dropBelowFloor(fieldColumns)
        return state
    }

    fun dropBelowFloor(fieldColumns: Collection<String>) {
        val floor = this[FLOOR_KEY] ?: return
        for (key in entries.keys.toList()) {
            if (key == FLOOR_KEY || key == ROW_STAMP_KEY || !isKnownKey(key, fieldColumns)) continue
            if (entries.getValue(key) <= floor) entries.remove(key)
        }
    }

    /** Purga los renglones quitados más de 90 días antes de la marca más nueva. Depende solo de las marcas: dos equipos purgan lo mismo. */
    fun purge(sheetColumns: Collection<String> = PatientSheets.COLUMNS) {
        val newest = newest() ?: return
        purgeBefore(horizonOf(newest), sheetColumns)
    }

    /** Purga los renglones quitados antes de [horizon] (`PurgeBefore`): al aceptar una consulta recibida, el horizonte es el de las marcas de aquí, no el de la copia. */
    fun purgeBefore(horizon: String, sheetColumns: Collection<String> = PatientSheets.COLUMNS) {
        for (column in sheetColumns) {
            for (key in entries.keys.toList()) {
                val parsed = parseRowKey(key, column) ?: continue
                if (!parsed.second) continue
                val removedAt = entries[key] ?: continue
                if (removedAt >= horizon) continue
                val rowKey = rowKey(column, parsed.first)
                // Una edición posterior lo revive: no se purga
                val edited = entries[rowKey]
                if (edited != null && edited > removedAt) continue
                entries.remove(key)
                entries.remove(rowKey)
            }
        }
    }

    companion object {
        const val ROW_STAMP_KEY = "UpdatedAtUtc"
        const val FLOOR_KEY = "#piso"
        const val REMOVED_SUFFIX = "/quitado"
        const val REMOVED_ROW_RETENTION_DAYS = 90L

        /** 90 días antes de [newest]: lo quitado antes de eso ya se purgó o se purgará en todos los equipos. */
        fun horizonOf(newest: String): String = ProtocolDate.format(ProtocolDate.parse(newest)!!.minusDays(REMOVED_ROW_RETENTION_DAYS))

        private val STAMP = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{7}$")

        /** Vacío, nulo, dañado, que no es objeto o con una llave repetida: sin marcas. Un valor que no es texto se ignora. */
        fun parse(json: String?): PatientFieldStamps {
            val stamps = PatientFieldStamps()
            if (json.isNullOrBlank()) return stamps
            try {
                val root = SyncJson.parse(json) as? JsonObject ?: return stamps
                if (SyncJson.hasRepeatedTopLevelKey(json)) return stamps
                root.forEach { (key, value) -> if (value is JsonPrimitive && value.isString) stamps[key] = value.content }
            } catch (_: SerializationException) {
                return PatientFieldStamps()
            }
            return stamps
        }

        /** Una marca del protocolo (se regresa ella misma), o `null`. */
        fun parseStamp(text: String?): String? = text?.takeIf { STAMP.matches(it) && ProtocolDate.parse(it) != null }

        fun rowKey(column: String, rowId: String) = "$column/$rowId"

        fun removedKey(column: String, rowId: String) = rowKey(column, rowId) + REMOVED_SUFFIX

        /** (RowId, si es la de quitado), o `null` si no es de un renglón de [column]. */
        fun parseRowKey(key: String, column: String): Pair<String, Boolean>? {
            val prefix = "$column/"
            if (!key.startsWith(prefix)) return null
            var rest = key.substring(prefix.length)
            val removed = rest.endsWith(REMOVED_SUFFIX)
            if (removed) rest = rest.removeSuffix(REMOVED_SUFFIX)
            if (rest.length != 36) return null
            val same = try {
                UUID.fromString(rest).toString() == rest
            } catch (_: IllegalArgumentException) {
                false
            }
            return if (same) rest to removed else null
        }

        fun isKnownKey(key: String, fieldColumns: Collection<String>): Boolean {
            if (key in fieldColumns) return true
            return PatientSheets.COLUMNS.any { key == it || parseRowKey(key, it) != null }
        }

        fun max(a: String?, b: String?): String? = when {
            a == null -> b
            b == null -> a
            a >= b -> a
            else -> b
        }

        fun compare(a: String?, b: String?): Int = when {
            a == null -> if (b == null) 0 else -1
            b == null -> 1
            else -> a.compareTo(b).coerceIn(-1, 1)
        }

        /** Una hoja que cambió en un guardado: su texto de antes y el de después. */
        data class SheetChange(val column: String, val before: String?, val after: String?)

        /** Lo que deja un guardado local (`ApplyLocalWrite`, regla «Pacientes por campo»). [previousUpdatedAtUtc] es `null` en un alta. */
        fun applyLocalWrite(
            before: String?,
            previousUpdatedAtUtc: String?,
            stamp: String,
            changedFields: Collection<String>,
            changedSheets: Collection<SheetChange>,
            patientSyncId: String,
            fieldColumns: Collection<String>,
            wholeRow: Boolean = false,
        ): PatientFieldStamps {
            val parsed = parse(before)
            val stamps = if (previousUpdatedAtUtc != null) parsed.state(previousUpdatedAtUtc, fieldColumns) else parsed.state(stamp, fieldColumns, stampless = false)
            if (wholeRow) {
                stamps[FLOOR_KEY] = stamp
            } else {
                changedFields.forEach { stamps[it] = stamp }
                for ((column, sheetBefore, sheetAfter) in changedSheets) {
                    val old = PatientSheets.readRows(column, sheetBefore, patientSyncId)
                    val current = PatientSheets.readRows(column, sheetAfter, patientSyncId)
                    val oldById = old.associateBy { it.rowId }
                    val currentIds = current.map { it.rowId }.toSet()
                    for (row in current) {
                        val previous = oldById[row.rowId]
                        if (previous == null || previous.text != row.text) stamps[rowKey(column, row.rowId)] = stamp
                    }
                    for (row in old) if (row.rowId !in currentIds) stamps[removedKey(column, row.rowId)] = stamp
                    if (old.map { it.rowId } != current.map { it.rowId }) stamps[column] = stamp
                }
            }
            stamps.dropBelowFloor(fieldColumns)
            stamps.purge()
            stamps[ROW_STAMP_KEY] = stamp
            return stamps
        }
    }
}
