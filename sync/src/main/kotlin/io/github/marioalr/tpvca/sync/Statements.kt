package io.github.marioalr.tpvca.sync

import io.github.marioalr.tpvca.sync.hrana.HranaValue
import io.github.marioalr.tpvca.sync.hrana.Statement

/**
 * El SQL que manda esta app, **literal** de `contracts/hrana/`: escrito de otra forma, SQLite puede recorrer la tabla completa y
 * Turso cobra cada fila. `StatementsContractTest` lo compara con `statements.json` y con las grabaciones.
 *
 * Las lecturas y `folio.*` son literales del contrato; las subidas (K5) tienen el mismo texto que la PC, comparado con `contracts/hrana/push.jsonl`. Ningún paso de esta app corre DDL.
 */
object Statements {
    /**
     * `meta.all`: paso 1 del primer arranque (plan §7.3). Es la única lectura que trae `_tpvc_meta.manifest`; lleva ~150 KB, así que solo
     * se hace al primer arranque y cuando cambia la revisión de la app.
     */
    const val META_ALL: String = "SELECT Key, Value FROM _tpvc_meta;"

    /** `handshake`: la hora del servidor, la marca de agua (5 s antes) y las claves de `_tpvc_meta`. */
    const val HANDSHAKE: String = "SELECT strftime('%Y-%m-%d %H:%M:%f','now') AS now, strftime('%Y-%m-%d %H:%M:%f','now','-5 seconds') AS wm, " +
        "(SELECT \"Value\" FROM \"_tpvc_meta\" WHERE \"Key\" = 'clinicId') AS \"clinicId\", " +
        "(SELECT \"Value\" FROM \"_tpvc_meta\" WHERE \"Key\" = 'generation') AS \"generation\", " +
        "(SELECT \"Value\" FROM \"_tpvc_meta\" WHERE \"Key\" = 'generationReady') AS \"generationReady\", " +
        "(SELECT \"Value\" FROM \"_tpvc_meta\" WHERE \"Key\" = 'generationPrevious') AS \"generationPrevious\", " +
        "(SELECT \"Value\" FROM \"_tpvc_meta\" WHERE \"Key\" = 'protocol') AS \"protocol\", " +
        "(SELECT \"Value\" FROM \"_tpvc_meta\" WHERE \"Key\" = 'minClientProtocol') AS \"minClientProtocol\", " +
        "(SELECT \"Value\" FROM \"_tpvc_meta\" WHERE \"Key\" = 'fullUploadAtUtc') AS \"fullUploadAtUtc\", " +
        "(SELECT \"Value\" FROM \"_tpvc_meta\" WHERE \"Key\" = 'stampsRepairedAtUtc') AS \"stampsRepairedAtUtc\";"

    /** `generation`: al final de cada página; si cambió, el equipo se detiene sin avanzar cursores. */
    const val GENERATION: String = "SELECT \"Value\" AS \"generation\" FROM \"_tpvc_meta\" WHERE \"Key\" = 'generation';"

    /** `profile`. */
    const val PROFILE: String = "SELECT * FROM ClinicProfiles WHERE Id = 1;"

    /** `heartbeat.readNonce`. */
    const val HEARTBEAT_READ_NONCE: String = "SELECT InstanceNonce FROM RegisteredDevices WHERE DeviceId = ?;"

    /** `heartbeat.write`. `LastSeenUtc` lo pone la nube. */
    const val HEARTBEAT_WRITE: String = "INSERT INTO RegisteredDevices (DeviceId, DeviceName, DeviceRole, AppVersion, Platform, SyncProtocol, " +
        "HandoffProtocol, ContractRevision, PendingCount, OldestPendingUtc, InstanceNonce, LastSeenUtc) " +
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, strftime('%Y-%m-%d %H:%M:%f','now')) " +
        "ON CONFLICT(DeviceId) DO UPDATE SET DeviceName = excluded.DeviceName, DeviceRole = excluded.DeviceRole, AppVersion = excluded.AppVersion, " +
        "Platform = excluded.Platform, SyncProtocol = excluded.SyncProtocol, HandoffProtocol = excluded.HandoffProtocol, " +
        "ContractRevision = excluded.ContractRevision, PendingCount = excluded.PendingCount, OldestPendingUtc = excluded.OldestPendingUtc, " +
        "InstanceNonce = excluded.InstanceNonce, LastSeenUtc = excluded.LastSeenUtc;"

    /** El latido al final de una subida (regla «Latido»): además, `LastPushUtc` con la hora de la nube, en la misma instrucción. Es la marca de cambios que leen los demás. */
    const val HEARTBEAT_AFTER_PUSH: String = "INSERT INTO RegisteredDevices (DeviceId, DeviceName, DeviceRole, AppVersion, Platform, SyncProtocol, " +
        "HandoffProtocol, ContractRevision, PendingCount, OldestPendingUtc, InstanceNonce, LastSeenUtc, LastPushUtc) " +
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, strftime('%Y-%m-%d %H:%M:%f','now'), strftime('%Y-%m-%d %H:%M:%f','now')) " +
        "ON CONFLICT(DeviceId) DO UPDATE SET DeviceName = excluded.DeviceName, DeviceRole = excluded.DeviceRole, AppVersion = excluded.AppVersion, " +
        "Platform = excluded.Platform, SyncProtocol = excluded.SyncProtocol, HandoffProtocol = excluded.HandoffProtocol, " +
        "ContractRevision = excluded.ContractRevision, PendingCount = excluded.PendingCount, OldestPendingUtc = excluded.OldestPendingUtc, " +
        "InstanceNonce = excluded.InstanceNonce, LastSeenUtc = excluded.LastSeenUtc, LastPushUtc = excluded.LastPushUtc;"

    /** `folio.take`, literal: el siguiente número de una serie en una sola instrucción. Argumentos: la serie, el piso y otra vez el piso. */
    const val FOLIO_TAKE: String = "INSERT INTO FolioCounters (Series, LastNumber) VALUES (?, ? + 1) " +
        "ON CONFLICT(Series) DO UPDATE SET LastNumber = max(LastNumber, ?) + 1 RETURNING LastNumber;"

    /** `folio.raise`, literal: sube el contador hasta un número que ya existe (un folio tecleado a mano). */
    const val FOLIO_RAISE: String = "INSERT INTO FolioCounters (Series, LastNumber) VALUES (?, ?) " +
        "ON CONFLICT(Series) DO UPDATE SET LastNumber = max(LastNumber, excluded.LastNumber);"

    /** La cerca de generación (protocolo 3): el último argumento es la generación del equipo. */
    private const val GENERATION_FENCE = "(SELECT \"Value\" FROM \"_tpvc_meta\" WHERE \"Key\" = 'generation') IS ?"

    private const val SERVER_NOW = "strftime('%Y-%m-%d %H:%M:%f','now')"

    private fun q(name: String) = "\"$name\""

    /** Las columnas de datos y luego las referencias, en el orden de la PC (`TableMap.RemoteColumnNames`). */
    fun dataColumns(table: SyncTable): List<SyncColumn> =
        table.columns.filter { it.role == "data" } + table.columns.filter { it.role == "reference" }

    /**
     * La subida de una fila (`TableMap.UpsertSql`): no entra si hay una lápida igual o más nueva ni si la nube cambió de generación, y sobre una fila que ya está solo gana una marca
     * estrictamente más nueva. `affected_row_count` 0 es una fila que la nube no aceptó (`golden/lww.json`).
     */
    fun upsertSql(table: SyncTable): String {
        val columns = dataColumns(table).map { it.name }
        val insert = (listOf("SyncId", "UpdatedAtUtc", "SyncedAtUtc", "OriginDeviceId") + columns).joinToString(", ", transform = ::q)
        val values = (listOf("?", "?", SERVER_NOW, "?") + columns.map { "?" }).joinToString(", ")
        val updates = (listOf("UpdatedAtUtc", "SyncedAtUtc", "OriginDeviceId") + columns).joinToString(", ") { "${q(it)} = excluded.${q(it)}" }
        return "INSERT INTO ${q(table.table)} ($insert) SELECT $values " +
            "WHERE NOT EXISTS (SELECT 1 FROM \"SyncTombstones\" WHERE \"EntityType\" = ? AND \"SyncId\" = ? AND \"DeletedAtUtc\" >= ?) " +
            "AND $GENERATION_FENCE " +
            "ON CONFLICT(\"SyncId\") DO UPDATE SET $updates " +
            "WHERE excluded.\"UpdatedAtUtc\" > ${q(table.table)}.\"UpdatedAtUtc\";"
    }

    /** La lápida: la nube se queda con el borrado más nuevo. Argumentos: EntityType, SyncId, DeletedAtUtc, OriginDeviceId, generación. */
    const val TOMBSTONE_UPSERT: String = "INSERT INTO \"SyncTombstones\" (\"EntityType\", \"SyncId\", \"DeletedAtUtc\", \"SyncedAtUtc\", \"OriginDeviceId\") " +
        "SELECT ?, ?, ?, $SERVER_NOW, ? WHERE $GENERATION_FENCE ON CONFLICT(\"EntityType\", \"SyncId\") DO UPDATE SET " +
        "\"DeletedAtUtc\" = excluded.\"DeletedAtUtc\", \"SyncedAtUtc\" = excluded.\"SyncedAtUtc\", \"OriginDeviceId\" = excluded.\"OriginDeviceId\" " +
        "WHERE excluded.\"DeletedAtUtc\" > \"SyncTombstones\".\"DeletedAtUtc\";"

    /** Después de su lápida: borra la fila si no es más nueva que el borrado. Argumentos: SyncId, DeletedAtUtc, generación. */
    fun deleteTombstonedSql(table: String): String = "DELETE FROM ${q(table)} WHERE \"SyncId\" = ? AND \"UpdatedAtUtc\" <= ? AND $GENERATION_FENCE;"

    /** Volver a leer lo que la nube no aceptó: la fila y su lápida. */
    fun rereadRowsSql(table: String, count: Int): String = "SELECT * FROM ${q(table)} WHERE \"SyncId\" IN (${List(count) { "?" }.joinToString(", ")});"

    fun rereadTombstonesSql(count: Int): String =
        "SELECT \"EntityType\", \"SyncId\", \"DeletedAtUtc\" FROM \"SyncTombstones\" WHERE \"EntityType\" = ? AND \"SyncId\" IN (${List(count) { "?" }.joinToString(", ")});"

    fun folioTake(series: String, floor: Int) =
        Statement(FOLIO_TAKE, listOf(HranaValue.Text(series), HranaValue.Integer(floor.toLong()), HranaValue.Integer(floor.toLong())))

    fun folioRaise(series: String, number: Int) = Statement(FOLIO_RAISE, listOf(HranaValue.Text(series), HranaValue.Integer(number.toLong())))

    /**
     * `meta.schema`: la huella. Una fila por su llave, en la misma petición de cada sincronización: si cambió se vuelve a leer el manifiesto (150 KB) y se pone o se quita el solo lectura, también cuando la app no cambió. La PC
     * reescribe el manifiesto cuando cambia la huella.
     */
    const val META_SCHEMA: String = "SELECT \"Value\" FROM \"_tpvc_meta\" WHERE \"Key\" = 'schema';"

    /** `registry.own`. */
    const val REGISTRY_OWN: String = "SELECT DeviceCode, Role, RevokedAtUtc, RevokedReason FROM DeviceRegistry WHERE DeviceId = ?;"

    /** `asset.meta`. */
    const val ASSET_META: String = "SELECT ByteCount, ChunkCount FROM ClinicAssets WHERE Hash = ?;"

    /** `asset.chunk`. */
    const val ASSET_CHUNK: String = "SELECT Content FROM ClinicAssetChunks WHERE Hash = ? AND Part = ?;"

    /** `page.<tabla>`, con su `LIMIT` y omitiendo lo propio salvo en una descarga completa (`PageQuery`). */
    fun pageSql(table: String, limit: Int, skipOwnRows: Boolean): String {
        val origin = if (skipOwnRows) " AND (\"OriginDeviceId\" IS NULL OR \"OriginDeviceId\" <> ?)" else ""
        return "SELECT * FROM \"$table\" WHERE \"SyncedAtUtc\" >= ? AND (\"SyncedAtUtc\" > ? OR (\"SyncedAtUtc\" = ? AND \"SyncId\" > ?))$origin " +
            "ORDER BY \"SyncedAtUtc\", \"SyncId\" LIMIT $limit;"
    }

    fun page(table: String, limit: Int, skipOwnRows: Boolean, syncedAtUtc: String, key: String, deviceId: String): Statement {
        val args = buildList<HranaValue> {
            repeat(3) { add(HranaValue.Text(syncedAtUtc)) }
            add(HranaValue.Text(key))
            if (skipOwnRows) add(HranaValue.Text(deviceId))
        }
        return Statement(pageSql(table, limit, skipOwnRows), args)
    }

    fun registryOwn(deviceId: String) = Statement(REGISTRY_OWN, listOf(HranaValue.Text(deviceId)))

    fun assetMeta(hash: String) = Statement(ASSET_META, listOf(HranaValue.Text(hash)))

    fun assetChunk(hash: String, part: Int) = Statement(ASSET_CHUNK, listOf(HranaValue.Text(hash), HranaValue.Integer(part.toLong())))

    val metaAll = Statement(META_ALL)
    val metaSchema = Statement(META_SCHEMA)
    val handshake = Statement(HANDSHAKE)
    val generation = Statement(GENERATION)
    val profile = Statement(PROFILE)
}
