package io.github.marioalr.tpvca.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Modelo de `contracts/sync/schema.json`, el contrato que exporta la PC.
 *
 * Solo lee: `ignoreUnknownKeys` acepta los cambios aditivos de revisiones nuevas. Kotlin nunca corre el `ddl` que trae
 * (la PC es la dueña del esquema de la nube); se conserva para comparar.
 */
@Serializable
data class SyncSchema(
    /** Revisión del contrato. */
    val contract: Int,
    /** Protocolo de sincronización de la PC que generó el archivo. */
    val protocol: Int,
    /** Huella del esquema que la PC guarda en `_tpvc_meta.schema`. */
    val fingerprint: String,
    val generatedBy: String? = null,
    /** Reglas escritas que cada equipo que escribe debe cumplir. */
    val rules: List<String> = emptyList(),
    /** Tablas sincronizadas, en el orden de subida. */
    val tables: List<SyncTable>,
    val tombstones: TombstoneTable,
    val otherTables: List<OtherTable> = emptyList(),
    val ddl: List<String> = emptyList(),
) {
    fun table(name: String): SyncTable? = tables.firstOrNull { it.table == name }

    /** La tabla de una clase de C# (`entityType`, también el `EntityType` de las lápidas y el `principal` de las referencias). */
    fun tableOf(entityType: String): SyncTable? = tables.firstOrNull { it.entityType == entityType }

    /** Nombre → número de cada `enumType` del manifiesto. */
    fun enums(): Map<String, Map<String, Int>> = tables.flatMap { it.columns }
        .mapNotNull { c -> c.enumType?.let { it to c.enumValues } }.toMap()

    companion object {
        val json: Json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): SyncSchema = json.decodeFromString(serializer(), text)
    }
}

@Serializable
data class SyncTable(
    val order: Int,
    val table: String,
    /** Nombre de la clase de C#: es el `EntityType` de las lápidas. */
    val entityType: String,
    val columns: List<SyncColumn>,
    val references: List<SyncReference> = emptyList(),
    val unique: List<JsonElement> = emptyList(),
    val ddl: List<String> = emptyList(),
) {
    fun column(name: String): SyncColumn? = columns.firstOrNull { it.name == name }
}

@Serializable
data class SyncColumn(
    val name: String,
    /** `system` (SyncId, marcas, OriginDeviceId) o `data`. */
    val role: String,
    val remoteType: String,
    val nullable: Boolean,
    val clrType: String? = null,
    /** En las fechas: `utc`, `clinicLocal` o `dateOnly`. */
    val timeKind: String? = null,
    /** Lo que se guarda si una columna obligatoria llega nula. */
    val nullValue: RemoteValue? = null,
    val enumType: String? = null,
    /** En los enums: nombre → número, en el orden de la PC. */
    val enumValues: Map<String, Int> = emptyMap(),
    val aliasOf: String? = null,
    /** Columna que cada equipo calcula: sube como información y no se aplica al bajar (salvo al crear la fila). */
    val derived: Boolean? = null,
    val note: String? = null,
)

@Serializable
data class RemoteValue(
    val type: String,
    val value: String? = null,
)

@Serializable
data class SyncReference(
    /** Columna con el SyncId del principal (sin llaves foráneas en la nube). */
    val column: String,
    val localProperty: String? = null,
    /** Clase de C# del principal. */
    val principal: String,
    val required: Boolean,
    val kind: String? = null,
    val onDelete: String? = null,
)

@Serializable
data class TombstoneTable(
    val table: String,
    val key: List<String>,
    val columns: List<JsonElement> = emptyList(),
    val ddl: List<String> = emptyList(),
)

@Serializable
data class OtherTable(
    val table: String,
    val replicated: Boolean? = null,
    val purpose: String? = null,
    val ddl: List<String> = emptyList(),
)
