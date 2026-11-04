package io.github.marioalr.tpvca.sync.engine

import io.github.marioalr.tpvca.sync.ProtocolDate
import io.github.marioalr.tpvca.sync.RemoteValue
import io.github.marioalr.tpvca.sync.SyncColumn
import io.github.marioalr.tpvca.sync.SyncContract
import io.github.marioalr.tpvca.sync.SyncTable
import io.github.marioalr.tpvca.sync.hrana.HranaValue
import io.github.marioalr.tpvca.sync.hrana.Row
import io.github.marioalr.tpvca.sync.hrana.ValueCodec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * De una fila de Hrana a la forma local, columna por columna según `schema.json`: enteros, booleanos y enums como números, decimales
 * como texto con su escala, fechas de 27 caracteres y `SyncId` en minúsculas. `SyncedAtUtc` se guarda tal cual: es la hora de la nube y
 * el cursor la compara como texto. Las columnas que la app no conoce se ignoran.
 */
object RowMapper {
    fun localValue(column: SyncColumn, value: HranaValue?): Any? {
        if (value == null || value is HranaValue.Null) {
            return if (column.nullable) null else column.nullValue?.let(::fromRemote) ?: emptyValue(column)
        }
        return when (column.remoteType) {
            "INTEGER" -> if (column.clrType == "bool") (if (ValueCodec.truth(value)) 1L else 0L) else ValueCodec.integerText(value).toLong()
            "REAL" -> value.text?.toDoubleOrNull() ?: 0.0
            "BLOB" -> ValueCodec.bytesOf(value)
            else -> when {
                column.name == "SyncedAtUtc" -> ValueCodec.textOf(value)
                column.name == SyncContract.PRIMARY_KEY || column.clrType == "Guid" -> ValueCodec.textOf(value).lowercase(java.util.Locale.ROOT)
                column.timeKind != null || column.clrType == "DateTime" -> ProtocolDate.canonical(value.text)
                column.clrType == "decimal" -> ValueCodec.decimalText(value)
                else -> ValueCodec.textOf(value)
            }
        }
    }

    /** La fila completa, para crearla: lo que no llegó (una nube sin esa columna) vale nulo o su `nullValue`. */
    fun newRow(table: SyncTable, row: Row): LocalRow = table.columns.associate { it.name to localValue(it, row[it.name]) }

    /** Lo que se actualiza en una fila que ya existe: lo que llegó, sin las derivadas (nunca se aplican al bajar). */
    fun update(table: SyncTable, row: Row): LocalRow = table.columns
        .filter { it.derived != true && it.name in row }
        .associate { it.name to localValue(it, row[it.name]) }

    /** De la forma local a como viaja: `ToRemoteValue` de la PC. */
    fun remoteValue(value: Any?): HranaValue = when (value) {
        null -> HranaValue.Null
        is Long -> HranaValue.Integer(value)
        is Int -> HranaValue.Integer(value.toLong())
        is Double -> HranaValue.Float(value)
        is ByteArray -> HranaValue.Blob(value)
        else -> HranaValue.Text(value.toString())
    }

    /** Toda la fila como viaja. */
    fun remoteRow(table: SyncTable, row: LocalRow): Row =
        java.util.TreeMap<String, HranaValue>(String.CASE_INSENSITIVE_ORDER).apply { table.columns.forEach { put(it.name, remoteValue(row[it.name])) } }

    private fun fromRemote(value: RemoteValue): Any? = when (value.type) {
        "integer" -> value.value?.toLong()
        "float" -> value.value?.toDouble()
        "null" -> null
        else -> value.value
    }

    private fun emptyValue(column: SyncColumn): Any = when (column.remoteType) {
        "INTEGER" -> 0L
        "REAL" -> 0.0
        "BLOB" -> ByteArray(0)
        else -> ""
    }

    /** Una fila remota como texto, para apartarla y volver a leerla igual. */
    fun serialize(row: Row): String = JsonObject(row.mapValues { it.value.toJson() }).toString()

    fun deserialize(payload: String): Row =
        java.util.TreeMap<String, HranaValue>(String.CASE_INSENSITIVE_ORDER).apply {
            Json.parseToJsonElement(payload).jsonObject.forEach { (column, value) -> put(column, HranaValue.fromJson(value)) }
        }
}
