package io.github.marioalr.tpvca.sync.rules

import io.github.marioalr.tpvca.sync.ProtocolDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * La combinación por campo de un expediente al bajarlo (`Services/Rules/PatientMerge.cs`, `golden/patient-merge.json`). Por campo gana la marca mayor, la marca vale al menos el piso de
 * su versión, y con marcas iguales el texto canónico mayor. Las hojas, por renglón. Conmutativa, idempotente y absorbente: por eso los equipos convergen sin subidas repetidas.
 */
object PatientMerge {
    /** De dónde sale el valor combinado de una columna. */
    enum class Source { LOCAL, REMOTE, MERGED }

    /** Una versión del expediente. En una hoja, [values] lleva el texto guardado. Una columna que falta en la remota cuenta como la de aquí. */
    class Version(val syncId: String, val updatedAtUtc: String, val fieldStampsJson: String?, val values: Map<String, JsonElement?>)

    class Result(
        val values: Map<String, JsonElement?>,
        val sources: Map<String, Source>,
        val stamps: PatientFieldStamps,
        val differsFromRemote: Boolean,
        val differsFromLocal: Boolean,
        val updatedAtUtc: String,
        val fieldStampsJson: String,
    )

    fun merge(local: Version, remote: Version, columns: Collection<String>): Result {
        val fieldColumns = fieldColumns(columns)
        val sl = PatientFieldStamps.parse(local.fieldStampsJson).state(local.updatedAtUtc, fieldColumns)
        val sr = PatientFieldStamps.parse(remote.fieldStampsJson).state(remote.updatedAtUtc, fieldColumns)
        val floorL = sl[PatientFieldStamps.FLOOR_KEY]
        val floorR = sr[PatientFieldStamps.FLOOR_KEY]

        val merged = PatientFieldStamps()
        for (key in sl.all.keys + sr.all.keys) {
            if (key == PatientFieldStamps.FLOOR_KEY || PatientFieldStamps.isKnownKey(key, fieldColumns)) continue
            merged[key] = PatientFieldStamps.max(sl[key], sr[key])
        }
        merged[PatientFieldStamps.FLOOR_KEY] = PatientFieldStamps.max(floorL, floorR)

        val values = LinkedHashMap<String, JsonElement?>()
        val sources = LinkedHashMap<String, Source>()
        val remoteValues = LinkedHashMap<String, JsonElement?>()
        for (column in columns) {
            val mine = local.values[column]
            val theirs = if (column in remote.values) remote.values[column] else mine
            remoteValues[column] = theirs

            if (PatientSheets.isSheet(column)) {
                val (text, source) = mergeSheet(column, CanonicalJson.asString(mine), CanonicalJson.asString(theirs), sl, sr, floorL, floorR, local.syncId, merged)
                values[column] = text?.let(::JsonPrimitive)
                sources[column] = source
                continue
            }

            val el = PatientFieldStamps.max(floorL, sl[column])
            val er = PatientFieldStamps.max(floorR, sr[column])
            merged[column] = PatientFieldStamps.max(el, er)
            val a = CanonicalJson.write(mine)
            val b = CanonicalJson.write(theirs)
            if (a == b) {
                values[column] = mine
                sources[column] = Source.LOCAL
                continue
            }
            var cmp = PatientFieldStamps.compare(el, er)
            if (cmp == 0) cmp = a.compareTo(b)
            values[column] = if (cmp > 0) mine else theirs
            sources[column] = if (cmp > 0) Source.LOCAL else Source.REMOTE
        }

        merged.dropBelowFloor(fieldColumns)
        merged.purge()

        val canonicalMerged = canonicalVersion(values, merged, columns, local.syncId)
        val differsFromRemote = canonicalMerged != canonicalVersion(remoteValues, purged(sr), columns, local.syncId)
        val differsFromLocal = canonicalMerged != canonicalVersion(local.values, purged(sl), columns, local.syncId)

        val stamp: String
        val stampsJson: String
        if (differsFromRemote) {
            val newest = maxOf(local.updatedAtUtc, remote.updatedAtUtc)
            stamp = ProtocolDate.format(ProtocolDate.parse(newest)!!.plusNanos(MERGE_STEP_NANOS))
            val withRow = merged.clone()
            withRow[PatientFieldStamps.ROW_STAMP_KEY] = stamp
            stampsJson = withRow.toJson()
        } else {
            stamp = remote.updatedAtUtc
            stampsJson = remote.fieldStampsJson.orEmpty()
        }
        return Result(values, sources, merged, differsFromRemote, differsFromLocal, stamp, stampsJson)
    }

    /** 1 ms: lo que se suma a la marca mayor cuando el resultado sube. */
    private const val MERGE_STEP_NANOS = 1_000_000L

    fun fieldColumns(columns: Collection<String>): Set<String> = columns.filterNot(PatientSheets::isSheet).toSet()

    private fun purged(state: PatientFieldStamps) = state.clone().also { it.purge() }

    private fun mergeSheet(
        column: String,
        textL: String?,
        textR: String?,
        sl: PatientFieldStamps,
        sr: PatientFieldStamps,
        floorL: String?,
        floorR: String?,
        syncId: String,
        merged: PatientFieldStamps,
    ): Pair<String?, Source> {
        val rowsL = PatientSheets.readRows(column, textL, syncId)
        val rowsR = PatientSheets.readRows(column, textR, syncId)
        val byL = rowsL.associateBy { it.rowId }
        val byR = rowsR.associateBy { it.rowId }
        val seqL = rowsL.map { it.rowId }
        val seqR = rowsR.map { it.rowId }

        val orderL = PatientFieldStamps.max(floorL, sl[column])
        val orderR = PatientFieldStamps.max(floorR, sr[column])
        merged[column] = PatientFieldStamps.max(orderL, orderR)

        val ids = LinkedHashSet(seqL)
        ids += seqR
        for (key in sl.all.keys + sr.all.keys) PatientFieldStamps.parseRowKey(key, column)?.let { ids += it.first }

        val live = HashSet<String>()
        val chosen = HashMap<String, PatientSheets.SheetRow>()
        for (id in ids) {
            val rowKey = PatientFieldStamps.rowKey(column, id)
            val removedKey = PatientFieldStamps.removedKey(column, id)
            val rowL = byL[id]
            val rowR = byR[id]
            val inL = rowL != null
            val inR = rowR != null

            val eL = if (inL) PatientFieldStamps.max(floorL, sl[rowKey]) else sl[rowKey]
            val rL = if (inL) sl[removedKey] else PatientFieldStamps.max(floorL, sl[removedKey])
            val eR = if (inR) PatientFieldStamps.max(floorR, sr[rowKey]) else sr[rowKey]
            val rR = if (inR) sr[removedKey] else PatientFieldStamps.max(floorR, sr[removedKey])

            val e = PatientFieldStamps.max(eL, eR)
            val removed = PatientFieldStamps.max(rL, rR)
            merged[rowKey] = e
            merged[removedKey] = removed

            if (!inL && !inR) continue
            if (removed != null && (e == null || PatientFieldStamps.compare(e, removed) <= 0)) continue
            live += id

            chosen[id] = if (rowL != null && rowR != null) {
                var cmp = PatientFieldStamps.compare(eL, eR)
                if (cmp == 0) cmp = rowL.text.compareTo(rowR.text)
                if (cmp >= 0) rowL else rowR
            } else {
                rowL ?: rowR!!
            }
        }

        val liveL = seqL.filter { it in live }
        val liveR = seqR.filter { it in live }
        var order = PatientFieldStamps.compare(orderL, orderR)
        if (order == 0) order = sequenceText(liveL).compareTo(sequenceText(liveR))
        val (first, second) = if (order >= 0) liveL to liveR else liveR to liveL
        val result = first + second.filter { it !in first }

        val rows = result.map { chosen.getValue(it) }
        val canonical = CanonicalJson.write(JsonArray(rows.map { it.canonical }))
        // Si dice lo mismo que una de las dos, se queda su texto; primero el de la nube
        if (canonical == PatientSheets.canonicalText(column, textR, syncId)) return textR to (if (textR == textL) Source.LOCAL else Source.REMOTE)
        if (canonical == PatientSheets.canonicalText(column, textL, syncId)) return textL to Source.LOCAL
        return PatientSheets.write(rows) to Source.MERGED
    }

    private fun sequenceText(ids: List<String>): String = CanonicalJson.write(JsonArray(ids.map(::JsonPrimitive)))

    /** Una versión por su significado: `{"stamps":{…},"values":{…}}` en forma canónica. Dos versiones que dicen lo mismo dan el mismo texto, en C# y en Kotlin. */
    fun canonicalVersion(values: Map<String, JsonElement?>, stamps: PatientFieldStamps, columns: Collection<String>, patientSyncId: String): String {
        val valuesNode = columns.associateWith { column ->
            val value = values[column]
            if (PatientSheets.isSheet(column)) PatientSheets.canonical(column, CanonicalJson.asString(value), patientSyncId) else value ?: kotlinx.serialization.json.JsonNull
        }
        val stampsNode = stamps.all.filterKeys { it != PatientFieldStamps.ROW_STAMP_KEY }.mapValues { JsonPrimitive(it.value) }
        return CanonicalJson.write(JsonObject(mapOf("stamps" to JsonObject(stampsNode), "values" to JsonObject(valuesNode))))
    }
}
