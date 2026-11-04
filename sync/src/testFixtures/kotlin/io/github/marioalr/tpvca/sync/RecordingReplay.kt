package io.github.marioalr.tpvca.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * Reproduce una grabación con `MockWebServer` (§7.6): cada petición de Kotlin se compara con la que sigue en la grabación y se responde
 * con lo grabado, salvo lo que no le toca a esta app, que se declara una por una:
 *
 * - [skipped]: los intercambios que son de la PC (la sonda con DDL, el latido de K3, su registro), con el motivo.
 * - Las dos lecturas de Kotlin que las grabaciones no tienen (revisión 21): `meta.all`, que no aparece en ninguna, y `registry.own`
 *   suelta, que solo aparece dentro del registro de la PC y con su rol. `meta.all` se responde con las claves del primer saludo grabado más
 *   el `schema.json`, y `registry.own` con [registryRow].
 *
 * Fuera de eso, cualquier diferencia es una falla, y también un SQL que no empiece con SELECT, BEGIN, COMMIT o ROLLBACK.
 */
class RecordingReplay(
    val transcript: Transcript,
    private val skipped: Map<Int, String> = emptyMap(),
    private val expectedToken: String = TOKEN,
) : AutoCloseable {
    private val server = MockWebServer()
    private val queue = ArrayDeque(transcript.exchanges.filterNot { it.n in skipped })

    val failures = ArrayList<String>()
    val sqlSeen = LinkedHashSet<String>()
    val extrasServed = ArrayList<String>()
    var httpRequests = 0
        private set
    val consumed = ArrayList<Int>()

    /** La primera fila del saludo grabado: de ahí salen las claves de `SELECT Key, Value FROM _tpvc_meta`. */
    private val firstHandshake: Map<String, JsonElement> by lazy {
        transcript.exchanges.firstNotNullOf { handshakeRow(it) }
    }

    /** Lo que responde `registry.own`. */
    var registryRow: List<JsonElement> = listOf(text("T1"), text("AndroidTablet"), NULL, NULL)
    var manifest: String = SyncContract.bundledSchemaText()

    val url: String get() = server.url("/").toString().trimEnd('/')

    val unconsumed: List<Int> get() = queue.map { it.n }

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = synchronized(this@RecordingReplay) { answer(request) }
        }
        server.start()
    }

    override fun close() = server.close()

    private fun answer(request: RecordedRequest): MockResponse {
        httpRequests++
        checkHeaders(request)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        val requests = body.getValue("requests").jsonArray

        for (statement in statementsOf(body)) {
            sqlSeen += statement.sql
            if (!READ_ONLY.containsMatchIn(statement.sql)) failures += "Instrucción que no es de lectura: ${statement.sql}"
        }

        val extras = requests.filter { it.isExecuteOf(Statements.META_ALL, Statements.REGISTRY_OWN) }
        val rest = requests - extras.toSet()
        extras.forEach { extrasServed += it.jsonObject.getValue("stmt").jsonObject.sql }

        // Solo lecturas de Kotlin (y el close): no le toca a la grabación
        if (rest.all { it.jsonObject["type"]?.jsonPrimitive?.content == "close" }) {
            return okResponse(extras.map { executeOk(it) } + CLOSE_RESULT)
        }

        val next = queue.removeFirstOrNull()
        if (next == null) {
            failures += "Una petición de más: la grabación ${transcript.scenario} ya se acabó. ${JsonObject(mapOf("requests" to JsonArray(rest)))}"
            return MockResponse().setResponseCode(500).setBody("""{"error":"fuera de la grabación"}""")
        }
        consumed += next.n
        sameMeaning(next.request, JsonObject(mapOf("requests" to JsonArray(rest))))?.let {
            failures += "Intercambio ${next.n} de ${transcript.scenario}: $it"
        }
        if (next.status != 200 || extras.isEmpty()) {
            return MockResponse().setResponseCode(next.status).setBody(next.responseText).setHeader("Content-Type", "application/json")
        }

        // Las lecturas sueltas de Kotlin van antes en los resultados; con la cuota agotada, también con error
        val recorded = Json.parseToJsonElement(next.responseText).jsonObject
        val results = recorded.getValue("results").jsonArray
        val firstError = results.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.content == "error" }
        return okResponse(extras.map { firstError ?: executeOk(it) } + results)
    }

    private fun checkHeaders(request: RecordedRequest) {
        if (request.method != "POST" || request.path != "/v2/pipeline") failures += "Petición a ${request.method} ${request.path}"
        if (request.getHeader("Authorization") != "Bearer $expectedToken") failures += "Sin el token en Authorization"
        val agent = request.getHeader("User-Agent").orEmpty()
        if (!USER_AGENT.matches(agent)) failures += "User-Agent inesperado: $agent"
        if (request.path.orEmpty().contains(expectedToken)) failures += "El token va en la URL"
    }

    private fun JsonElement.isExecuteOf(vararg sql: String): Boolean {
        val obj = jsonObject
        return obj["type"]?.jsonPrimitive?.content == "execute" && obj.getValue("stmt").jsonObject.sql in sql
    }

    private fun executeOk(request: JsonElement): JsonElement = when (request.jsonObject.getValue("stmt").jsonObject.sql) {
        Statements.META_ALL -> result(listOf("Key", "Value"), metaRows())
        else -> result(listOf("DeviceCode", "Role", "RevokedAtUtc", "RevokedReason"), listOf(registryRow))
    }

    private fun metaRows(): List<List<JsonElement>> = buildList {
        val schema = SyncContract.bundledSchema
        add(listOf(text("schema"), text(schema.fingerprint)))
        add(listOf(text("contract"), text(schema.contract.toString())))
        add(listOf(text("manifest"), text(manifest)))
        for ((key, value) in firstHandshake) {
            if (key != "now" && key != "wm") add(listOf(text(key), value))
        }
    }

    /** La fila de `registry.own` tal como la grabó la PC al registrarse: la de un equipo con rol de PC. */
    fun recordedRegistryRow(): List<JsonElement> {
        val exchange = transcript.exchanges.first { e -> e.statements.any { it.sql == Statements.REGISTRY_OWN } }
        val index = exchange.statements.indexOfFirst { it.sql == Statements.REGISTRY_OWN }
        val steps = Json.parseToJsonElement(exchange.responseText).jsonObject.getValue("results").jsonArray.first().jsonObject
            .getValue("response").jsonObject.getValue("result").jsonObject.getValue("step_results").jsonArray
        return steps[index].jsonObject.getValue("rows").jsonArray.first().jsonArray
    }

    private fun handshakeRow(exchange: Exchange): Map<String, JsonElement>? {
        val index = exchange.statements.indexOfFirst { it.sql == Statements.HANDSHAKE }
        if (index < 0 || exchange.status != 200) return null
        val results = Json.parseToJsonElement(exchange.responseText).jsonObject.getValue("results").jsonArray
        val first = results.first().jsonObject
        if (first["type"]?.jsonPrimitive?.content != "ok") return null
        val response = first.getValue("response").jsonObject.getValue("result").jsonObject
        val set = if ("step_results" in response) response.getValue("step_results").jsonArray[index].jsonObject else results[index].jsonObject
            .getValue("response").jsonObject.getValue("result").jsonObject
        val names = set.getValue("cols").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }
        val row = set.getValue("rows").jsonArray.first().jsonArray
        return names.zip(row).toMap()
    }

    private fun okResponse(results: List<JsonElement>) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
        .setBody(JsonObject(mapOf("baton" to NULL_LITERAL, "results" to JsonArray(results))).toString())

    private fun result(columns: List<String>, rows: List<List<JsonElement>>): JsonElement = buildJsonObject {
        put("type", "ok")
        put(
            "response",
            buildJsonObject {
                put("type", "execute")
                put(
                    "result",
                    buildJsonObject {
                        put("cols", buildJsonArray { columns.forEach { name -> add(buildJsonObject { put("name", name) }) } })
                        put("rows", JsonArray(rows.map { JsonArray(it) }))
                        put("affected_row_count", 0)
                    },
                )
            },
        )
    }

    companion object {
        const val TOKEN = "token-de-prueba"
        private val READ_ONLY = Regex("""^\s*(SELECT|BEGIN|COMMIT|ROLLBACK)\b""")
        private val USER_AGENT = Regex("""TPVC-Android/\d+\.\d+\.\d+""")
        private val NULL_LITERAL = JsonPrimitive(null as String?)
        private val NULL = buildJsonObject { put("type", "null") }
        private val CLOSE_RESULT = buildJsonObject {
            put("type", "ok")
            put("response", buildJsonObject { put("type", "close") })
        }

        fun text(value: String): JsonElement = buildJsonObject {
            put("type", "text")
            put("value", value)
        }

        /** Los motivos de lo que se salta, las notas de las grabaciones lo dicen. */
        const val PC_SCHEMA_PROBE = "La sonda del esquema con CREATE TABLE es de la PC; Kotlin nunca corre DDL. La huella llega con SELECT Key, Value."
        const val PC_JOIN_IDENTITY = "«Unirse» de la PC: Kotlin lee la identidad con SELECT Key, Value (paso 1 de §7.3)."
        const val HEARTBEAT_IN_K3 = "El latido se escribe desde K3: K2 no escribe nada en Turso."
        const val PC_REGISTRATION = "El registro en DeviceRegistry es de la PC; Kotlin lee su fila con registry.own antes de bajar (paso 3 de §7.3)."
    }
}
