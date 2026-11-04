package io.github.marioalr.tpvca.sync

import io.github.marioalr.tpvca.sync.hrana.HranaConnection
import io.github.marioalr.tpvca.sync.hrana.HranaException
import io.github.marioalr.tpvca.sync.hrana.HranaValue
import io.github.marioalr.tpvca.sync.hrana.ResultSet
import io.github.marioalr.tpvca.sync.hrana.Row
import io.github.marioalr.tpvca.sync.hrana.Snapshot
import io.github.marioalr.tpvca.sync.hrana.Statement
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.TreeMap

/**
 * La nube simulada de las pruebas de escritura: **ejecuta el SQL de verdad** en SQLite, con el DDL de `schema.json`, como `FakeHranaServer` de la PC (`docs/FAKE_HRANA_SPEC.md`). Sin transacción entre
 * instrucciones sueltas; el `batch` con BEGIN/COMMIT es atómico; la hora se inyecta reemplazando `'now'`; un recorrido completo de una tabla sincronizada falla con `SCAN_FORBIDDEN`.
 */
class SqlCloud(private val schema: SyncSchema = SyncContract.bundledSchema) {
    private val db: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")
    private val syncedTables = schema.tables.map { it.table }.toSet() + schema.tombstones.table

    var offline = false

    /** Cuántos segundos va adelante la nube. */
    var clockOffsetSeconds = 0L

    /** La n-ésima petición de la subida de esta sincronización: no llega ([Cut.Mode.BEFORE_APPLY]) o se aplica y se pierde su respuesta ([Cut.Mode.LOST_RESPONSE]). */
    var cut: Cut? = null

    /** Lo que se pidió, para revisar que ninguna instrucción sea DDL. */
    val executed = ArrayList<String>()

    data class Cut(val uploadRequest: Int, val mode: Mode) {
        enum class Mode { BEFORE_APPLY, LOST_RESPONSE }
    }

    private var uploadRequests = 0

    /** Una conexión como la que usa un equipo. */
    val connection: HranaConnection = object : HranaConnection {
        override fun query(statements: List<Statement>): List<ResultSet> = request(statements) { statements.map(::execute) }

        override fun snapshot(before: List<Statement>, statements: List<Statement>): Snapshot = request(before + statements) {
            val loose = before.map(::execute)
            Snapshot(loose, atomic(statements))
        }
    }

    /** Una petición que esta condición rechaza responde `RESPONSE_TOO_LARGE` sin ejecutar nada. */
    var tooLarge: (List<Statement>) -> Boolean = { false }

    /** La n-ésima petición de la subida responde 401, para probar lo que se corta a media subida. Solo los latidos con esto. */
    var failWith: ((List<Statement>) -> HranaException?)? = null

    private fun <T> request(statements: List<Statement>, run: () -> T): T = synchronized(this) {
        if (offline) throw HranaException(HranaException.NETWORK_ERROR, "Sin conexión con Turso.")
        failWith?.invoke(statements)?.let { throw it }
        if (tooLarge(statements)) throw HranaException(HranaException.RESPONSE_TOO_LARGE, "Demasiado grande.")
        val upload = statements.any { it.sql.contains("ON CONFLICT(\"SyncId\") DO UPDATE") || it.sql.contains("INSERT INTO \"SyncTombstones\"") }
        var lost = false
        if (upload) {
            uploadRequests++
            val active = cut
            if (active != null && uploadRequests == active.uploadRequest) {
                when (active.mode) {
                    Cut.Mode.BEFORE_APPLY -> throw HranaException(HranaException.NETWORK_ERROR, "Se cortó la red a media subida.")
                    Cut.Mode.LOST_RESPONSE -> lost = true
                }
            }
        }
        val result = run()
        if (lost) throw HranaException(HranaException.NETWORK_ERROR, "Se perdió la respuesta.")
        result
    }

    /** Empieza a contar otra vez las peticiones de la subida (al empezar una sincronización con corte). */
    fun armCut(value: Cut?) {
        cut = value
        uploadRequests = 0
    }

    private fun atomic(statements: List<Statement>): List<ResultSet> {
        db.autoCommit = false
        try {
            val results = statements.map(::execute)
            db.commit()
            return results
        } catch (e: Throwable) {
            db.rollback()
            throw e
        } finally {
            db.autoCommit = true
        }
    }

    fun nowText(): String = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(clockOffsetSeconds).format(STAMP)

    private fun withClock(sql: String) = sql.replace("'now'", "'${nowText()}'")

    private fun execute(statement: Statement): ResultSet {
        executed += statement.sql
        checkPlan(statement)
        return run(withClock(statement.sql), statement.args)
    }

    /** Lo que hace `ScanPolicy` en el simulador de la PC: una consulta que recorre una tabla sincronizada, en Turso, cobra cada fila. */
    private fun checkPlan(statement: Statement) {
        val sql = statement.sql.trimStart()
        if (!(sql.startsWith("SELECT") || sql.startsWith("DELETE") || sql.startsWith("UPDATE") || sql.startsWith("INSERT"))) return
        try {
            val plan = run("EXPLAIN QUERY PLAN ${withClock(statement.sql)}", statement.args)
            for (row in plan.rows) {
                val detail = row["detail"]?.text.orEmpty()
                val scanned = syncedTables.firstOrNull { table -> detail == "SCAN $table" || detail.startsWith("SCAN TABLE $table") || detail == "SCAN \"$table\"" }
                if (scanned != null) throw HranaException("SCAN_FORBIDDEN", "Recorre toda la tabla $scanned: ${statement.sql.take(120)}")
            }
        } catch (_: SQLException) {
            // Sin la tabla, la instrucción misma dirá qué falta
        }
    }

    private fun run(sql: String, args: List<HranaValue>): ResultSet {
        try {
            db.prepareStatement(sql).use { prepared ->
                args.forEachIndexed { index, value ->
                    when (value) {
                        is HranaValue.Null -> prepared.setObject(index + 1, null)
                        is HranaValue.Integer -> prepared.setLong(index + 1, value.value.toLong())
                        is HranaValue.Float -> prepared.setDouble(index + 1, value.text!!.toDouble())
                        is HranaValue.Blob -> prepared.setBytes(index + 1, value.bytes)
                        else -> prepared.setString(index + 1, value.text)
                    }
                }
                val hasRows = prepared.execute()
                val rows = ArrayList<Row>()
                if (hasRows) {
                    prepared.resultSet.use { results ->
                        val meta = results.metaData
                        while (results.next()) {
                            val row = TreeMap<String, HranaValue>(String.CASE_INSENSITIVE_ORDER)
                            for (i in 1..meta.columnCount) {
                                row[meta.getColumnLabel(i)] = when (val value = results.getObject(i)) {
                                    null -> HranaValue.Null
                                    is Int -> HranaValue.Integer(value.toLong())
                                    is Long -> HranaValue.Integer(value)
                                    is Double -> HranaValue.Float(value)
                                    is ByteArray -> HranaValue.Blob(value)
                                    else -> HranaValue.Text(value.toString())
                                }
                            }
                            rows += row
                        }
                    }
                }
                val writes = !(sql.trimStart().startsWith("SELECT") || sql.trimStart().startsWith("EXPLAIN"))
                val affected = if (writes) db.createStatement().use { s -> s.executeQuery("SELECT changes()").use { it.next(); it.getLong(1) } } else 0L
                return ResultSet(rows, affected)
            }
        } catch (e: SQLException) {
            throw HranaException("SQLITE_ERROR", "SQLite error: ${e.message}")
        }
    }

    // ------------------------------------------------------------- lo que hace el arnés, sin pasar por un equipo

    /** Corre SQL directo, como la PC (el DDL) o el arnés. */
    fun admin(sql: String, vararg args: Any?): List<Row> = synchronized(this) {
        run(withClock(sql), args.map { RowValue.of(it) }).rows
    }

    /** El esquema de la nube (el `ddl` de `schema.json`): solo lo crea la PC. Esta app nunca corre DDL. */
    fun createSchema() {
        schema.ddl.forEach { admin(it) }
        schema.tables.flatMap { it.ddl }.forEach { admin(it) }
        schema.tombstones.ddl.forEach { admin(it) }
        schema.otherTables.flatMap { it.ddl }.forEach { admin(it) }
    }

    fun hasTable(name: String) = admin("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?", name).isNotEmpty()

    private val held = HashMap<String, MutableList<Row>>()

    fun hold(table: String, syncId: String) {
        val rows = admin("SELECT * FROM \"$table\" WHERE \"SyncId\" = ?", syncId)
        held.getOrPut(table) { ArrayList() } += rows
        admin("DELETE FROM \"$table\" WHERE \"SyncId\" = ?", syncId)
    }

    fun release(table: String) {
        for (row in held.remove(table).orEmpty()) {
            val columns = row.keys.toList()
            val sql = "INSERT INTO \"$table\" (${columns.joinToString { "\"$it\"" }}) VALUES (${columns.joinToString { "?" }})"
            val values = columns.map { if (it.equals("SyncedAtUtc", ignoreCase = true)) HranaValue.Text(nowText()) else row.getValue(it) }
            synchronized(this) { run(sql, values) }
        }
    }

    private object RowValue {
        fun of(value: Any?): HranaValue = when (value) {
            null -> HranaValue.Null
            is HranaValue -> value
            is Int -> HranaValue.Integer(value.toLong())
            is Long -> HranaValue.Integer(value)
            else -> HranaValue.Text(value.toString())
        }
    }

    companion object {
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        val AHEAD_OF_ANY_TEST: Duration = Duration.ofDays(1)
    }
}
