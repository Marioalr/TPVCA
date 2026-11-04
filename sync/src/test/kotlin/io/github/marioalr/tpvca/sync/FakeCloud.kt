package io.github.marioalr.tpvca.sync

import io.github.marioalr.tpvca.sync.hrana.HranaConnection
import io.github.marioalr.tpvca.sync.hrana.HranaException
import io.github.marioalr.tpvca.sync.hrana.HranaValue
import io.github.marioalr.tpvca.sync.hrana.ResultSet
import io.github.marioalr.tpvca.sync.hrana.Row
import io.github.marioalr.tpvca.sync.hrana.Snapshot
import io.github.marioalr.tpvca.sync.hrana.Statement
import java.util.TreeMap

/** Una nube en memoria para las pruebas del motor: entiende exactamente las lecturas de [Statements] y nada más. */
class FakeCloud {
    val meta = linkedMapOf(
        "schema" to SyncContract.bundledSchema.fingerprint,
        "clinicId" to "clinica-1",
        "generation" to "gen-1",
        "generationReady" to "gen-1",
        "protocol" to "3",
        "minClientProtocol" to "2",
        "fullUploadAtUtc" to "2026-10-01 10:00:00.000",
        "manifest" to SyncContract.bundledSchemaText(),
    )
    val tables = LinkedHashMap<String, MutableList<Row>>()
    val assets = LinkedHashMap<String, List<ByteArray>>()
    var profile: Row? = null
    var registry: List<HranaValue>? = listOf(HranaValue.Text("C1"), HranaValue.Text("AndroidMobileAdmin"), HranaValue.Null, HranaValue.Null)
    var noMetaTable = false

    /** Los argumentos de cada latido escrito y el `InstanceNonce` de la fila, en `RegisteredDevices`. */
    val heartbeats = ArrayList<List<HranaValue>>()
    var rowNonce: String? = null

    /** Lo que se pidió: una lista de SQL por petición. */
    val requests = ArrayList<List<String>>()

    /** Falla o cambia algo antes de responder a una instrucción. */
    var onStatement: (Statement) -> Unit = {}
    var failSnapshot: (() -> HranaException)? = null

    /** `now` y la marca de agua que regresa el saludo. */
    var now = "2026-11-03 18:00:00.000"
    var watermark = "2026-11-03 17:59:55.000"

    fun add(table: String, syncId: String, updatedAtUtc: String, syncedAtUtc: String, vararg values: Pair<String, Any?>) {
        val row = rowOf("SyncId" to syncId, "UpdatedAtUtc" to updatedAtUtc, "SyncedAtUtc" to syncedAtUtc, "OriginDeviceId" to "device-pc", *values)
        val rows = tables.getOrPut(table) { ArrayList() }
        rows.removeAll { it["SyncId"]?.text == syncId }
        rows.add(row)
    }

    fun tombstone(entityType: String, syncId: String, deletedAtUtc: String, syncedAtUtc: String) {
        tables.getOrPut("SyncTombstones") { ArrayList() }.add(
            rowOf("EntityType" to entityType, "SyncId" to syncId, "DeletedAtUtc" to deletedAtUtc, "SyncedAtUtc" to syncedAtUtc, "OriginDeviceId" to "device-pc"),
        )
    }

    fun rowOf(vararg values: Pair<String, Any?>): Row = TreeMap<String, HranaValue>(String.CASE_INSENSITIVE_ORDER).apply {
        for ((column, value) in values) {
            put(
                column,
                when (value) {
                    null -> HranaValue.Null
                    is Int -> HranaValue.Integer(value.toLong())
                    is Long -> HranaValue.Integer(value)
                    is Double -> HranaValue.Float(value)
                    is ByteArray -> HranaValue.Blob(value)
                    else -> HranaValue.Text(value.toString())
                },
            )
        }
    }

    override fun toString() = "FakeCloud(${requests.size} peticiones)"

    val connection = object : HranaConnection {
        override fun query(statements: List<Statement>): List<ResultSet> {
            requests += statements.map { it.sql }
            return statements.map(::answer)
        }

        override fun snapshot(before: List<Statement>, statements: List<Statement>): Snapshot {
            requests += (before + statements).map { it.sql }
            failSnapshot?.let { throw it() }
            return Snapshot(before.map(::answer), statements.map(::answer))
        }
    }

    private fun answer(statement: Statement): ResultSet {
        onStatement(statement)
        val sql = statement.sql
        return when {
            sql == Statements.META_ALL -> {
                if (noMetaTable) throw HranaException("SQLITE_ERROR", "SQLite error: no such table: _tpvc_meta")
                ResultSet(meta.map { (key, value) -> rowOf("Key" to key, "Value" to value) })
            }
            sql == Statements.META_SCHEMA -> ResultSet(listOfNotNull(meta["schema"]?.let { rowOf("Value" to it) }))
            sql == Statements.HANDSHAKE -> ResultSet(
                listOf(
                    rowOf(
                        "now" to now, "wm" to watermark, "clinicId" to meta["clinicId"], "generation" to meta["generation"],
                        "generationReady" to meta["generationReady"], "generationPrevious" to meta["generationPrevious"], "protocol" to meta["protocol"],
                        "minClientProtocol" to meta["minClientProtocol"], "fullUploadAtUtc" to meta["fullUploadAtUtc"], "stampsRepairedAtUtc" to meta["stampsRepairedAtUtc"],
                    ),
                ),
            )
            sql == Statements.GENERATION -> ResultSet(listOf(rowOf("generation" to meta["generation"])))
            sql == Statements.PROFILE -> ResultSet(listOfNotNull(profile))
            sql == Statements.REGISTRY_OWN -> ResultSet(
                listOfNotNull(registry?.let { mapOf("DeviceCode" to it[0], "Role" to it[1], "RevokedAtUtc" to it[2], "RevokedReason" to it[3]) }),
            )
            sql == Statements.HEARTBEAT_READ_NONCE -> ResultSet(listOfNotNull(rowNonce?.let { rowOf("InstanceNonce" to it) }))
            sql == Statements.HEARTBEAT_WRITE -> {
                heartbeats += statement.args
                rowNonce = statement.args[10].text
                ResultSet(emptyList(), 1)
            }
            sql == Statements.ASSET_META -> {
                val parts = assets[statement.args[0].text]
                ResultSet(listOfNotNull(parts?.let { p -> rowOf("ByteCount" to p.sumOf { it.size }, "ChunkCount" to p.size) }))
            }
            sql == Statements.ASSET_CHUNK -> {
                val part = assets[statement.args[0].text]?.getOrNull(statement.args[1].text!!.toInt())
                ResultSet(listOfNotNull(part?.let { rowOf("Content" to it) }))
            }
            else -> page(statement)
        }
    }

    private fun page(statement: Statement): ResultSet {
        val match = PAGE.matchEntire(statement.sql) ?: throw HranaException("SQLITE_ERROR", "SQL que no es del contrato: ${statement.sql}")
        val (table, limit) = match.destructured
        val syncedAt = statement.args[0].text!!
        val key = statement.args[3].text!!
        val own = statement.args.getOrNull(4)?.text
        val rows = tables[table].orEmpty()
            .filter {
                val s = it["SyncedAtUtc"]?.text.orEmpty()
                s > syncedAt || (s == syncedAt && it["SyncId"]?.text.orEmpty() > key)
            }
            .filter { own == null || it["OriginDeviceId"]?.text != own }
            .sortedWith(compareBy({ it["SyncedAtUtc"]?.text }, { it["SyncId"]?.text }))
            .take(limit.toInt())
        return ResultSet(rows)
    }

    companion object {
        private val PAGE = Regex("""SELECT \* FROM "(\w+)" WHERE .* LIMIT (\d+);""")
    }
}
