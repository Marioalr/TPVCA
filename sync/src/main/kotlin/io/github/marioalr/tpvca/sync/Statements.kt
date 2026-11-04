package io.github.marioalr.tpvca.sync

import io.github.marioalr.tpvca.sync.hrana.HranaValue
import io.github.marioalr.tpvca.sync.hrana.Statement

/**
 * El SQL que manda esta app, **literal** de `contracts/hrana/`: escrito de otra forma, SQLite puede recorrer la tabla completa y
 * Turso cobra cada fila. `StatementsContractTest` lo compara con `statements.json` y con las grabaciones.
 *
 * Aquí solo hay lecturas: K2 no escribe nada en Turso, y ningún paso de esta app corre DDL.
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
    val handshake = Statement(HANDSHAKE)
    val generation = Statement(GENERATION)
    val profile = Statement(PROFILE)
}
