package io.github.marioalr.tpvca.sync

import io.github.marioalr.tpvca.domain.Clinic
import io.github.marioalr.tpvca.domain.devices.DeviceRole
import io.github.marioalr.tpvca.sync.engine.FolioLedger
import io.github.marioalr.tpvca.sync.engine.OutboxMark
import io.github.marioalr.tpvca.sync.engine.RowWrite
import io.github.marioalr.tpvca.sync.engine.SaleLine
import io.github.marioalr.tpvca.sync.engine.SavedChange
import io.github.marioalr.tpvca.sync.engine.StoreChanges
import io.github.marioalr.tpvca.sync.engine.SyncEngine
import io.github.marioalr.tpvca.sync.engine.SyncEngineConfig
import io.github.marioalr.tpvca.sync.engine.SyncOutcome
import io.github.marioalr.tpvca.sync.engine.SyncStore
import io.github.marioalr.tpvca.sync.engine.SyncTrigger
import io.github.marioalr.tpvca.sync.engine.WriteEngine
import io.github.marioalr.tpvca.sync.hrana.HranaConnection
import io.github.marioalr.tpvca.sync.hrana.HranaValue
import io.github.marioalr.tpvca.sync.rules.CanonicalJson
import io.github.marioalr.tpvca.sync.rules.FolioFactory
import io.github.marioalr.tpvca.sync.rules.PatientSheets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Corre `contracts/scenarios/` y compara cada paso con lo que grabó la PC (`expect`). **B es el motor de Kotlin, con sus casos de uso.** A depende de [pc]:
 * - **Sin él**, A **no es el motor de la PC:** es otra instancia del motor de Kotlin con los pasos de dueño de la nube simulados ([PcSimulator]), y la nube, [SqlCloud]. Comprueba que lo que B y la nube terminan teniendo
 *   es lo que grabó la PC con dos motores suyos; no comprueba que el motor real de la PC acepte lo que B sube.
 * - **Con él** ([RealPcControl]), A es el motor real de la PC sobre `FakeHranaServer`, y B le habla por HTTP con el cliente de verdad.
 *
 * [storeFor] da la base de B: en memoria en `:sync`, Room en `:data`.
 */
class ScenarioHarness(private val file: File, private val pc: RealPcControl? = null, private val storeFor: (String) -> SyncStore = { InMemorySyncStore() }) {
    private val schema = SyncContract.bundledSchema
    private val scenario = Json.parseToJsonElement(file.readText()).jsonObject
    private val watch: Map<String, List<String>> = scenario.getValue("watch").jsonObject.mapValues { (_, v) -> v.jsonArray.map { it.jsonPrimitive.content } }
    private val watched = schema.tables.filter { it.table in watch }
    private val cloud = SqlCloud()
    private val ids = LinkedHashMap<String, String>()
    private val anchors = HashMap<String, LocalDateTime>()
    private val devices = LinkedHashMap<String, Device>()
    val differences = ArrayList<String>()

    /** Lo que `folios.json` guarda fuera de la base. */
    class MemoryLedger : FolioLedger {
        val values = HashMap<String, Int>()

        override fun last(letteredSeries: String) = values[letteredSeries] ?: 0

        override fun reserve(letteredSeries: String, localMax: Int): Int {
            val next = maxOf(values[letteredSeries] ?: 0, localMax) + 1
            values[letteredSeries] = next
            return next
        }
    }

    inner class Device(val name: String, val deviceId: String, val role: DeviceRole, val code: String) {
        val store: SyncStore = if (role == DeviceRole.WindowsDesktop) InMemorySyncStore() else storeFor(name)
        val ledger = MemoryLedger()
        val connection: HranaConnection get() = remote ?: cloud.connection
        val writes = WriteEngine(store, { deviceId }, ledger, { connection }, roleOverride = if (role == DeviceRole.WindowsDesktop) role else null)

        fun engine(pushChunk: Int? = null) = SyncEngine(
            connection,
            store,
            SyncEngineConfig(deviceId, pushChunkSize = pushChunk ?: 80, allowedRoles = setOf(DeviceRole.WindowsDesktop, DeviceRole.AndroidTablet, DeviceRole.AndroidMobileAdmin)),
        )
    }

    /** La conexión de B con el simulador de la PC, por HTTP. */
    private var remote: HranaConnection? = null

    fun run(): List<String> {
        remote = pc?.newCloud(JsonObject(watch.mapValues { (_, columns) -> JsonArray(columns.map(::JsonPrimitive)) }))
        for (device in scenario.getValue("devices").jsonArray.map { it.jsonObject }) {
            val name = device.getValue("name").jsonPrimitive.content
            val role = DeviceRole.fromName(device.getValue("role").jsonPrimitive.content)!!
            if (pc != null && role == DeviceRole.WindowsDesktop) continue
            devices[name] = Device(name, device.getValue("deviceId").jsonPrimitive.content, role, if (role == DeviceRole.WindowsDesktop) "P1" else "T1")
        }
        for (element in scenario.getValue("steps").jsonArray) {
            val step = element.jsonObject
            val n = step.getValue("n").jsonPrimitive.int
            val op = step.getValue("op").jsonPrimitive.content
            val where = "paso $n ($op${step["device"]?.jsonPrimitive?.content?.let { " en $it" }.orEmpty()})"
            val result = try {
                execute(step)
            } catch (e: Exception) {
                differences += "$where: ${e::class.simpleName}: ${e.message}"
                return differences
            }
            step["result"]?.let { expected ->
                val actual = result ?: JsonNull
                if (CanonicalJson.write(expected) != CanonicalJson.write(actual)) differences += "$where: result esperado ${CanonicalJson.write(expected)}, salió ${CanonicalJson.write(actual)}"
            }
            compare(where, step.getValue("expect").jsonObject, snapshot())
            if (differences.isNotEmpty()) return differences
            // Con la nube adelantada, cada equipo marca con el desfase que midió, y dos mediciones difieren por la latencia y hasta por los 250 ms de tolerancia. Dos ediciones seguidas no
            // tienen un orden fijo si van más cerca que eso: la del escenario sería una moneda al aire en una máquina lenta (pasó en la CI). No cambia lo esperado, solo separa los pasos
            if (op == "serverClock") skewedClock = true
            Thread.sleep(if (skewedClock) SKEWED_STEP_MILLIS else 5)
        }
        return differences
    }

    // ------------------------------------------------------------------------------------------------------ los pasos

    private fun execute(step: JsonObject): JsonElement? {
        val op = step.getValue("op").jsonPrimitive.content
        // Con la PC de verdad, lo de A y lo del arnés lo hace ella
        if (pc != null && (step["device"]?.jsonPrimitive?.content == "A" || step["device"] == null)) return pc.step(JsonObject(step.filterKeys { it !in setOf("n", "result", "expect") }))
        if (pc != null && op == "join") {
            val device = devices.getValue(step.getValue("device").jsonPrimitive.content)
            pc.approve(device.deviceId, device.code, device.role.name, device.name)
            return JsonObject(mapOf("ok" to JsonPrimitive(true)))
        }
        fun s(key: String) = step.getValue(key).jsonPrimitive.content
        fun d() = devices.getValue(s("device"))
        return when (op) {
            "network" -> {
                cloud.offline = !step.getValue("online").jsonPrimitive.booleanOrNull!!
                null
            }
            "serverClock" -> {
                cloud.clockOffsetSeconds = step.getValue("offsetSeconds").jsonPrimitive.int.toLong()
                null
            }
            "cloudHold" -> {
                cloud.hold(s("table"), s("syncId"))
                null
            }
            "cloudRelease" -> {
                cloud.release(s("table"))
                null
            }
            "fullSync" -> PcSimulator(d()).fullSync()
            "join" -> {
                PcSimulator(devices.getValue("A")).approve(d())
                JsonObject(mapOf("ok" to JsonPrimitive(true)))
            }
            "sync" -> sync(d(), step)
            "create" -> {
                val row = step.getValue("row").jsonObject
                val table = s("table")
                val values = LinkedHashMap<String, Any?>(row.filterKeys { it != "SyncId" }.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull })
                // Lo que la PC trae por omisión en la clase y no viene en el paso
                if (schema.table(table)!!.column("IsActive") != null && "IsActive" !in values) values["IsActive"] = "1"
                d().writes.create(table, values, row.getValue("SyncId").jsonPrimitive.content)
                null
            }
            "edit" -> {
                val set = step.getValue("set").jsonObject.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull }
                check(d().writes.edit(s("table"), s("syncId"), set)) { "La fila ${s("syncId")} no existe" }
                null
            }
            "delete" -> {
                d().writes.delete(s("table"), s("syncId"))
                null
            }
            "rawWrite" -> {
                rawWrite(d(), s("table"), s("syncId"), step.getValue("set").jsonObject, s("stamp"), step["anchor"]?.jsonPrimitive?.content)
                null
            }
            "sheetRow" -> {
                sheetRow(d(), s("syncId"), s("action"), step.getValue("row").jsonObject)
                null
            }
            "newPatient" -> {
                val (_, folio) = d().writes.newPatient(s("fullName"), mapOf("Age" to "30"), s("syncId"))
                JsonObject(mapOf("recordNumber" to JsonPrimitive(mask(folio))))
            }
            "sell" -> {
                val product = s("productSyncId")
                val price = d().store.select("Products", listOf("Price"), "SyncId", listOf(product)).single()["Price"] as String
                val sale = d().writes.registerSale(listOf(SaleLine(productSyncId = product, quantity = step.getValue("quantity").jsonPrimitive.int, unitPrice = BigDecimal(price))), "Público General", "Efectivo")!!
                JsonObject(mapOf("folio" to JsonPrimitive(mask(sale.folio))))
            }
            "cancelSale" -> JsonObject(mapOf("ok" to JsonPrimitive(d().writes.cancelSale(d().writes.findSale(unmask(s("folio"))) ?: error("No está el ticket ${s("folio")}")))))
            "closeBatch" -> {
                d().writes.closeBatch(s("syncId"))
                null
            }
            "regularizeFolios" -> PcSimulator(d()).regularizeFolios()
            "backup" -> {
                PcSimulator(d()).backup()
                null
            }
            "restoreBackup" -> {
                PcSimulator(d()).restoreBackup()
                null
            }
            "replaceCloud" -> PcSimulator(d()).replaceCloud()
            "reapplySaved" -> {
                d().engine().reapplySaved()
                JsonObject(mapOf("ok" to JsonPrimitive(true)))
            }
            else -> error("Paso desconocido: $op")
        }
    }

    private fun sync(device: Device, step: JsonObject): JsonElement {
        val cut = step["cut"]?.jsonObject?.let {
            SqlCloud.Cut(it.getValue("uploadRequest").jsonPrimitive.int, if (it.getValue("mode").jsonPrimitive.content == "beforeApply") SqlCloud.Cut.Mode.BEFORE_APPLY else SqlCloud.Cut.Mode.LOST_RESPONSE)
        }
        cloud.armCut(cut)
        try {
            return resultOf(device, device.engine(step["pushBatchSize"]?.jsonPrimitive?.int).sync(SyncTrigger.USER))
        } finally {
            cloud.armCut(null)
        }
    }

    private fun resultOf(device: Device, outcome: SyncOutcome): JsonElement =
        JsonObject(mapOf("ok" to JsonPrimitive(outcome is SyncOutcome.Completed), "parked" to JsonPrimitive(device.store.parkedRows().size)))

    /** Sin pasar por el guardado ni tocar `FieldStampsJson`: una PC v1.19–v1.38, un reloj que saltó o dos equipos en el mismo instante. Solo aquí se escribe así; la nota sigue anotada para subir, como el disparador de la PC. */
    private fun rawWrite(device: Device, table: String, syncId: String, set: JsonObject, stamp: String, anchor: String?) {
        val match = Regex("^now(?:\\+([0-9]+)([smh]))?$").matchEntire(stamp) ?: error("Marca desconocida: $stamp")
        val at = (if (pc != null && anchor != null) ProtocolDate.parse(pc.anchor(anchor, stamp)) else anchor?.let(anchors::get)) ?: run {
            var time = LocalDateTime.now(ZoneOffset.UTC)
            val (amount, unit) = match.destructured
            if (amount.isNotEmpty()) {
                time = when (unit) {
                    "s" -> time.plusSeconds(amount.toLong())
                    "m" -> time.plusMinutes(amount.toLong())
                    else -> time.plusHours(amount.toLong())
                }
            }
            if (anchor != null) anchors[anchor] = time
            time
        }
        val definition = schema.table(table)!!
        val values = set.mapValues { (name, value) ->
            val text = (value as? JsonPrimitive)?.contentOrNull
            io.github.marioalr.tpvca.sync.engine.RowMapper.localValue(definition.column(name)!!, text?.let(HranaValue::Text))
        }
        device.store.apply(
            StoreChanges(
                writes = listOf(RowWrite(table, syncId, values + ("UpdatedAtUtc" to ProtocolDate.format(at)), isNew = false)),
                outbox = listOf(OutboxMark(definition.entityType, syncId, ProtocolDate.format(at))),
            ),
        )
    }

    private fun sheetRow(device: Device, patient: String, action: String, row: JsonObject) {
        val rowId = row.getValue("RowId").jsonPrimitive.content
        device.writes.editSheet(patient, PatientSheets.SUPPLIES) { rows ->
            when (action) {
                "add" -> rows.map { it.raw } + JsonObject(row)
                "remove" -> rows.filter { it.rowId != rowId }.map { it.raw }
                "setQuantity" -> rows.map { sheet ->
                    if (sheet.rowId == rowId) JsonObject(sheet.raw + ("Quantity" to row.getValue("Quantity"))) else sheet.raw
                }
                else -> error("Acción desconocida en la hoja: $action")
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------ A: la PC simulada

    /** Lo que solo hace la PC, simulado sobre [SqlCloud]: **no es el motor de la PC.** Sus reglas salen de `TursoSyncService.cs` y sus resultados se comparan con lo que ella grabó. */
    private inner class PcSimulator(private val device: Device) {
        fun fullSync(): JsonElement {
            if (!cloud.hasTable("_tpvc_meta")) cloud.createSchema()
            val generation = cloud.admin("SELECT \"Value\" FROM \"_tpvc_meta\" WHERE \"Key\" = 'generation'").firstOrNull()?.get("Value")?.text ?: UUID.randomUUID().toString()
            val clinic = cloud.admin("SELECT \"Value\" FROM \"_tpvc_meta\" WHERE \"Key\" = 'clinicId'").firstOrNull()?.get("Value")?.text ?: UUID.randomUUID().toString()
            meta("schema", schema.fingerprint)
            meta("clinicId", clinic)
            meta("protocol", "3")
            meta("minClientProtocol", "2")
            meta("manifest", SyncContract.bundledSchemaText())
            meta("generation", generation)
            meta("generationReady", generation)
            meta("fullUploadAtUtc", cloud.nowText())
            approve(device)
            device.store.apply(
                StoreChanges(
                    meta = mapOf(
                        SyncEngine.CLINIC_ID to clinic, SyncEngine.GENERATION to generation, SyncEngine.DEVICE_ROLE to device.role.name, SyncEngine.DEVICE_CODE to device.code,
                        SyncEngine.MANIFEST_CONTRACT to SyncContract.REVISION.toString(), SyncEngine.FIRST_DOWNLOAD_DONE to "ya",
                    ),
                ),
            )
            enqueueAll()
            return resultOf(device, device.engine().sync(SyncTrigger.USER))
        }

        private fun meta(key: String, value: String) {
            cloud.admin("INSERT INTO \"_tpvc_meta\" (\"Key\", \"Value\") VALUES (?, ?) ON CONFLICT(\"Key\") DO UPDATE SET \"Value\" = excluded.\"Value\"", key, value)
        }

        fun approve(target: Device) {
            cloud.admin(
                "INSERT INTO DeviceRegistry (DeviceId, DeviceCode, Role, DisplayName, Model, PairedAtUtc) VALUES (?, ?, ?, ?, '', ?) ON CONFLICT(DeviceId) DO NOTHING",
                target.deviceId, target.code, target.role.name, target.name, cloud.nowText(),
            )
        }

        private fun enqueueAll() {
            val now = cloud.nowText()
            val marks = schema.tables.flatMap { table ->
                device.store.select(table.table, listOf("SyncId")).map { OutboxMark(table.entityType, it["SyncId"] as String, now) }
            }
            device.store.apply(StoreChanges(outbox = marks))
        }

        /** La regularización diaria (8.17): cada expediente con letra toma el siguiente número de su serie y guarda el suyo en `OriginalFolio`; sube por el ciclo normal. */
        fun regularizeFolios(): JsonElement {
            var patients = 0
            for (row in device.store.select("Patients", listOf("SyncId", "RecordNumber"))) {
                val lettered = FolioFactory.parseLettered(row["RecordNumber"] as String?) ?: continue
                val number = cloud.connection.query(listOf(Statements.folioTake(lettered.series, 0))).single().rows.single()["LastNumber"]!!.text!!.toInt()
                device.writes.edit(
                    "Patients",
                    row["SyncId"] as String,
                    mapOf("RecordNumber" to FolioFactory.format(lettered.kind, lettered.series, number), "OriginalFolio" to row["RecordNumber"]),
                )
                patients++
            }
            return JsonObject(mapOf("outcome" to JsonPrimitive("Done"), "patients" to JsonPrimitive(patients), "sales" to JsonPrimitive(0)))
        }

        fun backup() {
            backupOfA = (device.store as InMemorySyncStore).backup()
        }

        fun restoreBackup() {
            (device.store as InMemorySyncStore).restoreBackup(backupOfA!!)
            // Una base restaurada no tiene cursor de subida: en la PC, todo lo que trae cuenta como pendiente
            enqueueAll()
        }

        /** «Reemplazar la nube» (8.9): generación nueva, guarda lo que la nube tenía y el respaldo no, la vacía, sube lo de aquí y al final `generationReady`. */
        fun replaceCloud(): JsonElement {
            val current = cloud.admin("SELECT \"Value\" FROM \"_tpvc_meta\" WHERE \"Key\" = 'generation'").single()["Value"]!!.text!!
            val next = UUID.randomUUID().toString()
            meta("generationPrevious", current)
            meta("generation", next)
            meta("minClientProtocol", "3")
            val lost = ArrayList<SavedChange>()
            val now = cloud.nowText()
            for (table in schema.tables) {
                val local = device.store.select(table.table, listOf("SyncId", "UpdatedAtUtc")).associate { it["SyncId"] as String to it["UpdatedAtUtc"] as String }
                for (row in cloud.admin("SELECT * FROM \"${table.table}\"")) {
                    val id = row["SyncId"]!!.text!!
                    val mine = local[id]
                    if (mine == null || ProtocolDate.canonical(row["UpdatedAtUtc"]?.text) > mine) {
                        lost += SavedChange(0, "nube", current, table.entityType, id, SavedChange.KIND_ROW, io.github.marioalr.tpvca.sync.engine.RowMapper.serialize(row), 3, SyncContract.REVISION, now)
                    }
                }
                cloud.admin("DELETE FROM \"${table.table}\"")
            }
            cloud.admin("DELETE FROM \"SyncTombstones\"")
            device.store.apply(StoreChanges(saved = lost, meta = mapOf(SyncEngine.GENERATION to next)))
            enqueueAll()
            val outcome = device.engine().sync(SyncTrigger.USER)
            meta("generationReady", next)
            return JsonObject(mapOf("ok" to JsonPrimitive(outcome is SyncOutcome.Completed)))
        }
    }

    private var backupOfA: List<Any?>? = null

    private var skewedClock = false

    // ------------------------------------------------------------------------------------------------------ la foto

    private fun canonicalValue(table: String, column: String, raw: Any?, syncId: String): String? {
        val text = raw?.toString() ?: return null
        val definition = schema.table(table)!!.column(column)
        val value = when {
            definition == null -> text.lowercase(java.util.Locale.ROOT)
            definition.role == "reference" -> text.lowercase(java.util.Locale.ROOT)
            definition.clrType == "decimal" -> CanonicalJson.number(text)
            definition.clrType == "DateTime" -> ProtocolDate.canonical(text)
            definition.clrType == "bool" -> if (text == "1" || text.equals("true", ignoreCase = true)) "1" else "0"
            else -> text
        }
        if (table != "Patients") return value
        if (PatientSheets.isSheet(column)) return PatientSheets.canonicalText(column, value, syncId)
        if (column == "FieldStampsJson" && value.isNotEmpty()) return CanonicalJson.normalize(value)
        return value
    }

    private fun cloudRows(): Map<String, List<Map<String, String?>>> = watched.associate { table ->
        val rows = if (!cloud.hasTable(table.table)) emptyList() else cloud.admin("SELECT * FROM \"${table.table}\"").map { row ->
            val id = row["SyncId"]!!.text!!
            buildMap { put("SyncId", id); watch.getValue(table.table).forEach { put(it, canonicalValue(table.table, it, row[it]?.let { value -> if (value is HranaValue.Null) null else value.text }, id)) } }
        }
        table.table to rows
    }

    private fun localRows(device: Device): Map<String, List<Map<String, String?>>> = watched.associate { table ->
        val columns = listOf("SyncId") + watch.getValue(table.table)
        table.table to device.store.select(table.table, columns).map { row ->
            val id = row["SyncId"] as String
            buildMap { put("SyncId", id); watch.getValue(table.table).forEach { put(it, canonicalValue(table.table, it, row[it], id)) } }
        }
    }

    private fun tablesOf(node: JsonObject): Map<String, List<Map<String, String?>>> = watched.associate { table ->
        table.table to node[table.table]?.jsonArray.orEmpty().map { row -> row.jsonObject.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull } }
    }

    private fun snapshot(): JsonObject {
        val places = ArrayList<Pair<String, Map<String, List<Map<String, String?>>>>>()
        val real = pc?.snapshot()
        places += "cloud" to (real?.let { tablesOf(it.getValue("cloud").jsonObject) } ?: cloudRows())
        if (real != null) places += "A" to tablesOf(real.getValue("A").jsonObject)
        devices.forEach { (name, device) -> places += name to localRows(device) }
        numberNewIds(places)
        val expect = LinkedHashMap<String, JsonElement>()
        for ((place, tables) in places) {
            val state = LinkedHashMap<String, JsonElement>()
            for ((table, rows) in tables) {
                val byId = java.util.TreeMap<String, JsonElement>()
                for (row in rows) {
                    val values = LinkedHashMap<String, JsonElement>()
                    watch.getValue(table).forEach { values[it] = row[it]?.let { text -> JsonPrimitive(mask(text)) } ?: JsonNull }
                    byId[mask(row.getValue("SyncId")!!)] = JsonObject(values)
                }
                state[table] = JsonObject(byId)
            }
            if (place == "cloud") {
                val types = watched.map { it.entityType }.toSet()
                val tombstones = if (real != null) {
                    real.getValue("tombstones").jsonArray.map { element ->
                        val (type, id) = element.jsonPrimitive.content.split("/")
                        mask("$type/${id.lowercase(java.util.Locale.ROOT)}")
                    }.sorted()
                } else if (!cloud.hasTable("SyncTombstones")) {
                    emptyList()
                } else {
                    cloud.admin("SELECT EntityType, SyncId FROM SyncTombstones").filter { it["EntityType"]!!.text in types }
                        .map { mask("${it["EntityType"]!!.text}/${it["SyncId"]!!.text!!.lowercase(java.util.Locale.ROOT)}") }.sorted()
                }
                state["tombstones"] = JsonArray(tombstones.map(::JsonPrimitive))
            } else if (place == "A" && real != null) {
                state["pending"] = real.getValue("pending")
                state["parked"] = real.getValue("parked")
                state["saved"] = real.getValue("saved")
            } else {
                val store = devices.getValue(place).store
                state["pending"] = JsonPrimitive(store.pendingChanges())
                state["parked"] = JsonPrimitive(store.parkedRows().size)
                state["saved"] = JsonPrimitive(store.savedChanges().size)
            }
            expect[place] = JsonObject(state)
        }
        return JsonObject(expect)
    }

    private fun isFixedId(text: String) = text.startsWith("10000000-0000-4000-8000-", ignoreCase = true)

    /** Los GUID que generó el motor, en un orden que no depende de la corrida: lugares, tablas y filas por su contenido con esos GUID tapados (`NumberNewIds`). */
    private fun numberNewIds(places: List<Pair<String, Map<String, List<Map<String, String?>>>>>) {
        fun masked(row: Map<String, String?>) = row.entries.sortedBy { it.key }.joinToString("\u0001") { (key, value) ->
            key + "=" + if (value == null) "∅" else ANY_GUID.replace(value) { m -> if (isFixedId(m.value) || m.value.lowercase(java.util.Locale.ROOT) in ids) m.value else "?" }
        }
        for ((_, tables) in places) {
            for (table in watched) {
                for (row in tables.getValue(table.table).sortedBy(::masked)) {
                    for (column in listOf("SyncId") + watch.getValue(table.table)) {
                        val value = row[column] ?: continue
                        for (m in ANY_GUID.findAll(value)) {
                            val id = m.value.lowercase(java.util.Locale.ROOT)
                            if (!isFixedId(id) && id !in ids) ids[id] = "<id:${ids.size + 1}>"
                        }
                    }
                }
            }
        }
    }

    private fun today() = Clinic.today(java.time.Instant.now())

    private fun mask(input: String): String {
        var text = ANY_GUID.replace(input) { m ->
            val id = m.value.lowercase(java.util.Locale.ROOT)
            if (isFixedId(id)) id else ids[id] ?: m.value
        }
        text = TIMESTAMP.replace(text) { if (it.value == FIXTURE_DATE || it.value == FIXTURE_BIRTH) it.value else "<time>" }
        CODES.forEach { (code, name) -> text = text.replace("-$code-", "-<code:$name>-") }
        return text.replace("TKT-" + today().format(DAY), "TKT-<today>").replace("EXP-" + today().year, "EXP-<year>")
    }

    private fun unmask(input: String): String {
        var text = input
        CODES.forEach { (code, name) -> text = text.replace("-<code:$name>-", "-$code-") }
        return text.replace("TKT-<today>", "TKT-" + today().format(DAY)).replace("EXP-<year>", "EXP-" + today().year)
    }

    private fun compare(where: String, expected: JsonObject, actual: JsonObject, path: String = "") {
        for ((key, value) in expected) {
            val other = actual[key]
            val here = "$path/$key"
            if (value is JsonObject && other is JsonObject) {
                compare(where, value, other, here)
            } else if (CanonicalJson.write(value) != CanonicalJson.write(other)) {
                differences += "$where $here: esperado ${CanonicalJson.write(value).take(600)}, salió ${CanonicalJson.write(other).take(600)}"
            }
        }
        for (key in actual.keys - expected.keys) differences += "$where $path/$key: sobra ${CanonicalJson.write(actual[key]).take(300)}"
    }

    private companion object {
        const val SKEWED_STEP_MILLIS = 750L

        /** El código de cada equipo: A es la primera PC y B la primera tablet. */
        val CODES = listOf("P1" to "A", "T1" to "B")
        val ANY_GUID = Regex("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b")
        val TIMESTAMP = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}[ T][0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,7})?")
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
        const val FIXTURE_DATE = "2026-01-15 10:30:00.0000000"
        const val FIXTURE_BIRTH = "1985-03-02 00:00:00.0000000"

    }
}
