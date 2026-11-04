package io.github.marioalr.tpvca.sync.engine

import io.github.marioalr.tpvca.domain.Clinic
import io.github.marioalr.tpvca.domain.patients.PatientIdentity
import io.github.marioalr.tpvca.sync.ProtocolDate
import io.github.marioalr.tpvca.sync.SyncColumn
import io.github.marioalr.tpvca.sync.SyncContract
import io.github.marioalr.tpvca.sync.SyncRules
import io.github.marioalr.tpvca.sync.SyncSchema
import io.github.marioalr.tpvca.sync.SyncTable
import io.github.marioalr.tpvca.sync.hrana.HranaValue
import io.github.marioalr.tpvca.sync.rules.CanonicalJson
import io.github.marioalr.tpvca.sync.rules.PatientFieldStamps
import io.github.marioalr.tpvca.sync.rules.PatientMerge
import io.github.marioalr.tpvca.sync.rules.PatientSheets
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Lo que `planDelete` decide: qué filas se van y qué referencias quedan en nulo. */
class DeletePlan {
    val deletes = HashMap<String, Set<String>>()
    val nullify = ArrayList<Nullify>()
}

/**
 * Escribir en la base local como un cambio de este equipo: la marca monótona con el desfase, las marcas por campo de un expediente y la cola de salida, **todo en la misma
 * transacción**. Los valores llegan en la forma de la nube (texto). Sin permisos: quien llama desde afuera pasa por [WriteEngine].
 */
class LocalWriter(
    private val store: SyncStore,
    private val deviceId: () -> String,
    private val schema: SyncSchema = SyncContract.bundledSchema,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun table(name: String): SyncTable = requireNotNull(schema.table(name)) { "Tabla sin sincronizar: $name" }

    fun tableOf(entityType: String): SyncTable = requireNotNull(schema.tableOf(entityType)) { "Tipo sin sincronizar: $entityType" }

    /** `max(hora + desfase, guardada + 1 ms)` (`golden/stamping.json`). */
    fun stamp(stored: String?): String {
        val now = ProtocolDate.format(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
        return SyncRules.nextLocalStamp(now, stored, store.meta(SyncEngine.CLOCK_OFFSET_MS)?.toLongOrNull() ?: 0L)
    }

    fun mark(table: SyncTable, syncId: String) = OutboxMark(table.entityType, syncId, utcNow())

    private fun utcNow() = ProtocolDate.format(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))

    fun find(table: SyncTable, syncId: String): LocalRow? =
        store.select(table.table, table.columns.map { it.name }, "SyncId", listOf(syncId)).firstOrNull()

    fun localValue(column: SyncColumn, value: Any?): Any? = when (value) {
        null -> RowMapper.localValue(column, null)
        is String -> RowMapper.localValue(column, HranaValue.Text(value))
        is Boolean -> if (value) 1L else 0L
        is Int -> value.toLong()
        else -> value
    }

    private fun values(table: SyncTable, set: Map<String, Any?>): LocalRow = set.mapValues { (name, value) ->
        localValue(requireNotNull(table.column(name)) { "${table.table} no tiene la columna $name" }, value)
    }

    /** Una fila nueva, con lo que no se dio en su valor de omisión. Regresa su `SyncId`. */
    fun create(tableName: String, syncId: String, set: Map<String, Any?>): LocalRow = store.transaction {
        val table = table(tableName)
        val stamp = stamp(null)
        val row = buildRow(table, syncId, set, stamp)
        store.apply(StoreChanges(writes = listOf(RowWrite(table.table, syncId, row, isNew = true)), outbox = listOf(mark(table, syncId))))
        row
    }

    fun buildRow(table: SyncTable, syncId: String, set: Map<String, Any?>, stamp: String): LocalRow {
        val base = table.columns.associate { it.name to RowMapper.localValue(it, null) }
        val row = base + values(table, set) + mapOf("SyncId" to syncId, "UpdatedAtUtc" to stamp, "OriginDeviceId" to deviceId())
        if (table.table != PatientSummaryRecalculator.PATIENTS) return row
        // Un alta no marca campos: solo la marca de la fila
        val json = PatientFieldStamps.applyLocalWrite(null, null, stamp, emptyList(), emptyList(), syncId, patientFieldColumns(table)).toJson()
        return row + ("FieldStampsJson" to json)
    }

    /** Lo que cambió, con marca nueva y anotado para subir. Nada, si no cambió nada: una edición sin cambios no sube. `false` si ya no existe. */
    fun edit(tableName: String, syncId: String, set: Map<String, Any?>): Boolean = store.transaction {
        val table = table(tableName)
        val current = find(table, syncId) ?: return@transaction false
        val next = values(table, set)
        val changed = next.filter { (name, value) -> current[name] != value }
        if (changed.isEmpty()) return@transaction true
        val stamp = stamp(current["UpdatedAtUtc"] as String?)
        val write = if (table.table == PatientSummaryRecalculator.PATIENTS) changed + patientStamp(table, current, changed, stamp) else changed
        store.apply(
            StoreChanges(
                writes = listOf(RowWrite(table.table, syncId, write + ("UpdatedAtUtc" to stamp) + ("OriginDeviceId" to deviceId()), isNew = false)),
                outbox = listOf(mark(table, syncId)),
            ),
        )
        true
    }

    /** El sello de un guardado (`StampLocalWrite`): marca lo que cambió por su significado, no por su texto. */
    private fun patientStamp(table: SyncTable, current: LocalRow, changed: LocalRow, stamp: String): Pair<String, Any?> {
        val syncId = current["SyncId"] as String
        val fields = ArrayList<String>()
        val sheets = ArrayList<PatientFieldStamps.Companion.SheetChange>()
        val merged = patientColumns(table).map { it.name }.toSet()
        for ((name, value) in changed) {
            if (name !in merged) continue
            if (PatientSheets.isSheet(name)) {
                val before = current[name] as String?
                val after = value as String?
                if (PatientSheets.canonicalText(name, before, syncId) != PatientSheets.canonicalText(name, after, syncId)) {
                    sheets += PatientFieldStamps.Companion.SheetChange(name, before, after)
                }
            } else {
                val column = table.column(name)!!
                if (CanonicalJson.write(CanonicalJson.fromValue(column.clrType, current[name])) != CanonicalJson.write(CanonicalJson.fromValue(column.clrType, value))) fields += name
            }
        }
        val json = PatientFieldStamps.applyLocalWrite(
            current["FieldStampsJson"] as String?, current["UpdatedAtUtc"] as String?, stamp, fields, sheets, syncId, patientFieldColumns(table),
        ).toJson()
        return "FieldStampsJson" to json
    }

    /**
     * «Volver a aplicar» (`RestampAsync`): la fila que ya está aquí toma una marca nueva y sube. Un expediente sube el piso: es una escritura de la fila completa.
     */
    fun restamp(table: SyncTable, syncId: String) = store.transaction {
        val current = find(table, syncId) ?: return@transaction
        val stamp = stamp(current["UpdatedAtUtc"] as String?)
        val write = LinkedHashMap<String, Any?>()
        write["UpdatedAtUtc"] = stamp
        write["OriginDeviceId"] = deviceId()
        if (table.table == PatientSummaryRecalculator.PATIENTS) {
            write["FieldStampsJson"] = PatientFieldStamps.applyLocalWrite(
                current["FieldStampsJson"] as String?, current["UpdatedAtUtc"] as String?, stamp, emptyList(), emptyList(), syncId, patientFieldColumns(table), wholeRow = true,
            ).toJson()
        }
        store.apply(StoreChanges(writes = listOf(RowWrite(table.table, syncId, write, isNew = false)), outbox = listOf(mark(table, syncId))))
    }

    /**
     * Borrar como un cambio de este equipo: cada fila que se va (también en cascada) deja su lápida y se anota; una referencia que queda en nulo es un cambio de esa fila. `false` si una
     * referencia `restrict` lo impide. Sin fila no hay nada que hacer.
     */
    fun delete(tableName: String, syncId: String): Boolean = store.transaction {
        val table = table(tableName)
        val stored = store.stamps(table.table, listOf(syncId))[syncId] ?: return@transaction true
        val plan = DeletePlan()
        if (!planDelete(table, setOf(syncId), plan)) return@transaction false
        val stamp = stamp(stored)
        val tombstones = plan.deletes.flatMap { (name, ids) ->
            val entityType = table(name).entityType
            ids.map { TombstoneRow(entityType, it, stamp, "", deviceId()) }
        }
        val marks = plan.deletes.flatMap { (name, ids) -> ids.map { mark(table(name), it) } }
        val gone = plan.deletes.mapValues { it.value }
        val nulled = plan.nullify.filter { it.syncIds.any { id -> id !in gone[it.table].orEmpty() } }
        val writes = nulled.flatMap { nullify ->
            val other = table(nullify.table)
            (nullify.syncIds - gone[nullify.table].orEmpty()).map { RowWrite(nullify.table, it, mapOf("UpdatedAtUtc" to stamp, "OriginDeviceId" to deviceId()), isNew = false) to mark(other, it) }
        }
        store.apply(
            StoreChanges(
                deletes = plan.deletes,
                nullify = plan.nullify,
                writes = writes.map { it.first },
                tombstones = tombstones,
                outbox = marks + writes.map { it.second },
            ),
        )
        true
    }

    /** `false` si una `restrict` lo impide. Recorre las referencias de `schema.json`: la base local no tiene llaves foráneas. */
    fun planDelete(table: SyncTable, ids: Set<String>, plan: DeletePlan): Boolean {
        val known = plan.deletes[table.table].orEmpty()
        val fresh = ids - known
        if (fresh.isEmpty()) return true
        plan.deletes[table.table] = known + fresh
        for (dependent in schema.tables) {
            for (reference in dependent.references.filter { it.principal == table.entityType }) {
                val rows = store.referencing(dependent.table, reference.column, fresh)
                if (rows.isEmpty()) continue
                when (reference.onDelete) {
                    "restrict" -> return false
                    "cascade" -> if (!planDelete(dependent, rows, plan)) return false
                    "setNull" -> plan.nullify += Nullify(dependent.table, reference.column, rows)
                }
            }
        }
        return true
    }

    companion object {
        /** Las columnas que se combinan por campo: las que viajan, menos las derivadas y `FieldStampsJson`. */
        fun patientColumns(table: SyncTable): List<SyncColumn> =
            table.columns.filter { (it.role == "data" || it.role == "reference") && it.derived != true && it.name != "FieldStampsJson" }

        fun patientFieldColumns(table: SyncTable): Set<String> = PatientMerge.fieldColumns(patientColumns(table).map { it.name })

        /**
         * Una versión para combinar, con los valores como los lee la PC. La edad con fecha de nacimiento se lee al día en los dos lados (`PatientRecord.Age`): si se comparara el número
         * guardado, dos equipos verían una diferencia que no existe.
         */
        fun patientVersion(table: SyncTable, row: LocalRow, syncId: String, clock: Clock): PatientMerge.Version {
            val values = LinkedHashMap<String, JsonElement?>()
            for (column in patientColumns(table)) {
                val value = row[column.name]
                values[column.name] = when {
                    PatientSheets.isSheet(column.name) -> (value as String?)?.let(::JsonPrimitive)
                    column.name == "Age" -> CanonicalJson.fromValue(column.clrType, ageOf(row, clock) ?: value)
                    else -> CanonicalJson.fromValue(column.clrType, value)
                }
            }
            return PatientMerge.Version(syncId, row["UpdatedAtUtc"] as String, row["FieldStampsJson"] as String?, values)
        }

        private fun ageOf(row: LocalRow, clock: Clock): Int? {
            val birth = ProtocolDate.parse(row["BirthDate"] as String?)?.toLocalDate() ?: return null
            return PatientIdentity.calculateAge(birth, Clinic.today(clock.instant()))
        }
    }
}
