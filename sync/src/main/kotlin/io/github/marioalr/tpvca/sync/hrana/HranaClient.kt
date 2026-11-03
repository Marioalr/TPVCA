package io.github.marioalr.tpvca.sync.hrana

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URI
import java.util.Locale
import java.util.TreeMap
import java.util.concurrent.TimeUnit

/** Una instrucción: el SQL literal y sus argumentos. */
data class Statement(val sql: String, val args: List<HranaValue> = emptyList())

/** Una fila: sus columnas sin distinguir mayúsculas, como `TursoClient.ParseRows`. */
typealias Row = Map<String, HranaValue>

fun Row.text(column: String): String? = this[column]?.text

/** Lo que regresa una instrucción. Las estadísticas que agrega Turso (`rows_read`…) se ignoran. */
data class ResultSet(val rows: List<Row>, val affectedRows: Long = 0)

/** Lo que regresa una petición con unas instrucciones sueltas antes de la transacción de lectura. */
data class Snapshot(val before: List<ResultSet>, val statements: List<ResultSet>)

/** Lo que el motor usa de Turso (pruebas: `MockWebServer` con las grabaciones). */
interface HranaConnection {
    /** Varias instrucciones en una petición, cada una se confirma sola (`QueryBatchAsync` de la PC). */
    fun query(statements: List<Statement>): List<ResultSet>

    /** [before] sueltas y luego [statements] en una sola foto: `batch` con BEGIN/COMMIT (V14), todo en una petición. */
    fun snapshot(before: List<Statement>, statements: List<Statement>): Snapshot
}

/**
 * Cliente Hrana v2 por HTTP con OkHttp: `POST {url}/v2/pipeline` con `Authorization: Bearer`, sin baton, solo `https://`.
 * Reproduce `Services/TursoClient.cs`. El cuerpo de error nunca incluye el token, ni el token va en una URL.
 */
class HranaClient(
    url: String,
    token: String,
    appVersion: String,
    http: OkHttpClient = defaultHttp(),
) : HranaConnection {
    private val pipelineUrl = PipelineUrl.normalize(url)
    private val bearer = cleanToken(token)
    private val userAgent = "TPVC-Android/$appVersion"
    private val http = http.newBuilder().followRedirects(false).followSslRedirects(false).build()

    override fun query(statements: List<Statement>): List<ResultSet> {
        val results = send(statements.map(::executeRequest) + CLOSE)
        return parsing { statements.indices.map { parseExecute(results.getOrNull(it)) } }
    }

    override fun snapshot(before: List<Statement>, statements: List<Statement>): Snapshot {
        val results = send(before.map(::executeRequest) + batchRequest(statements) + CLOSE)
        return parsing {
            val loose = before.indices.map { parseExecute(results.getOrNull(it)) }

            val batch = results.getOrNull(before.size)?.jsonObject
            checkResultError(batch)
            val result = batch?.get("response")?.jsonObject?.get("result")?.jsonObject
            // El primer error manda: los pasos que siguen se saltan y la transacción se deshace
            result?.get("step_errors")?.jsonArray?.firstOrNull { it !is JsonNull }?.let(::throwServiceError)
            val steps = result?.get("step_results")?.jsonArray
            // El paso 0 es BEGIN
            Snapshot(loose, statements.indices.map { parseResult(steps?.getOrNull(it + 1)) })
        }
    }

    /** Un JSON con otra forma es un error de la respuesta, no una caída de la app. */
    private inline fun <T> parsing(block: () -> T): T = try {
        block()
    } catch (e: IllegalArgumentException) {
        throw HranaException(HranaException.BAD_RESPONSE, "La respuesta de Turso no tiene la forma esperada.", cause = e)
    }

    private fun send(requests: List<JsonObject>): JsonArray {
        val body = JsonObject(mapOf("requests" to JsonArray(requests))).toString()
        val request = Request.Builder()
            .url(pipelineUrl)
            .header("Authorization", "Bearer $bearer")
            .header("Accept", "application/json")
            .header("User-Agent", userAgent)
            .post(body.toRequestBody(JSON_TYPE))
            .build()

        val text: String
        val status: Int
        try {
            http.newCall(request).execute().use { response ->
                status = response.code
                text = response.body.string()
            }
        } catch (e: InterruptedIOException) {
            throw HranaException(HranaException.TIMEOUT, "Tiempo de espera agotado al conectar con Turso.", cause = e)
        } catch (e: IOException) {
            throw HranaException(HranaException.NETWORK_ERROR, "No se pudo conectar con Turso: ${e.message}. Verifique la conexión a internet o la URL.", cause = e)
        }

        if (status !in 200..299) throw HranaException.fromHttp(status, text)
        val root = try {
            Json.parseToJsonElement(text).jsonObject
        } catch (e: IllegalArgumentException) {
            throw HranaException(HranaException.BAD_RESPONSE, "La respuesta de Turso no contiene un JSON válido.", cause = e)
        }
        return root["results"]?.jsonArray ?: JsonArray(emptyList())
    }

    private fun parseExecute(result: JsonElement?): ResultSet {
        val obj = result?.jsonObject
        checkResultError(obj)
        return parseResult(obj?.get("response")?.jsonObject?.get("result"))
    }

    private fun parseResult(result: JsonElement?): ResultSet {
        val obj = result?.takeUnless { it is JsonNull }?.jsonObject ?: return ResultSet(emptyList())
        val names = obj["cols"]?.jsonArray.orEmpty().mapIndexed { i, col ->
            col.jsonObject["name"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content ?: "col_$i"
        }
        val rows = obj["rows"]?.jsonArray.orEmpty().map { row ->
            val map = TreeMap<String, HranaValue>(String.CASE_INSENSITIVE_ORDER)
            row.jsonArray.forEachIndexed { i, value -> names.getOrNull(i)?.let { map[it] = HranaValue.fromJson(value) } }
            map as Row
        }
        return ResultSet(rows, obj["affected_row_count"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0)
    }

    private fun checkResultError(result: JsonObject?) {
        if (result?.get("type")?.jsonPrimitive?.content.equals("error", ignoreCase = true)) {
            throwServiceError(result!!["error"] ?: JsonObject(emptyMap()))
        }
    }

    private fun throwServiceError(error: JsonElement): Nothing {
        val obj = error.jsonObject
        val message = obj["message"]?.jsonPrimitive?.content ?: "Error desconocido en Turso"
        throw HranaException.fromResult(obj["code"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content, message)
    }

    companion object {
        private val JSON_TYPE = "application/json".toMediaType()
        private val CLOSE = JsonObject(mapOf("type" to JsonPrimitive("close")))

        /** 30 s por petición, como la PC; ante un tiempo agotado el motor pide la mitad de filas. */
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()

        /** Quita comillas, saltos de línea y un `Bearer` pegado por accidente (`TursoClient.CleanToken`). */
        fun cleanToken(token: String): String {
            val clean = token.trim().trim('"', '\'', '`').filterNot { it == '\r' || it == '\n' || it == ' ' }
            return if (clean.startsWith("Bearer", ignoreCase = true)) clean.substring(6).trim('"', '\'', '`') else clean
        }

        private fun statementJson(statement: Statement): JsonObject = JsonObject(
            buildMap {
                put("sql", JsonPrimitive(statement.sql))
                if (statement.args.isNotEmpty()) put("args", JsonArray(statement.args.map { it.toJson() }))
            },
        )

        private fun executeRequest(statement: Statement) =
            JsonObject(mapOf("type" to JsonPrimitive("execute"), "stmt" to statementJson(statement)))

        private fun ok(step: Int) = JsonObject(mapOf("type" to JsonPrimitive("ok"), "step" to JsonPrimitive(step)))

        /** BEGIN, cada instrucción solo si la anterior salió bien, COMMIT, y ROLLBACK si algo falló (`TransactionAsync`). */
        private fun batchRequest(statements: List<Statement>): JsonObject {
            val steps = buildList {
                add(JsonObject(mapOf("stmt" to statementJson(Statement("BEGIN")))))
                statements.forEachIndexed { i, statement ->
                    add(JsonObject(mapOf("condition" to ok(i), "stmt" to statementJson(statement))))
                }
                add(JsonObject(mapOf("condition" to ok(statements.size), "stmt" to statementJson(Statement("COMMIT")))))
                val notCommitted = JsonObject(mapOf("type" to JsonPrimitive("not"), "cond" to ok(statements.size + 1)))
                add(JsonObject(mapOf("condition" to notCommitted, "stmt" to statementJson(Statement("ROLLBACK")))))
            }
            return JsonObject(
                mapOf("type" to JsonPrimitive("batch"), "batch" to JsonObject(mapOf("steps" to JsonArray(steps)))),
            )
        }
    }
}

/** La URL del pipeline: `libsql://` y `turso://` pasan a `https://`; `http://` solo en este mismo equipo (el servidor de pruebas). */
object PipelineUrl {
    fun normalize(url: String): String {
        var clean = url.trim().trim('"', '\'', '`').substringBefore('?')
        require(clean.isNotEmpty()) { "La URL de la base de datos de Turso no puede estar vacía." }
        val lower = clean.lowercase(Locale.ROOT)
        clean = when {
            lower.startsWith("libsql://") -> "https://" + clean.substring(9)
            lower.startsWith("turso://") -> "https://" + clean.substring(8)
            lower.startsWith("http://") || lower.startsWith("https://") -> clean
            else -> "https://$clean"
        }.trimEnd('/')
        if (!clean.lowercase(Locale.ROOT).endsWith("/v2/pipeline")) clean += "/v2/pipeline"

        val uri = URI(clean)
        val host = uri.host.orEmpty()
        val loopback = host.equals("localhost", ignoreCase = true) || host == "127.0.0.1"
        require('.' in host || loopback) { "La dirección «$host» parece ser solo el nombre de la base: Turso pide la URL completa." }
        require(uri.scheme.equals("https", ignoreCase = true) || loopback) {
            "La dirección usa http:// sin cifrar: el token de Turso viajaría en claro. Use la URL libsql:// o https:// que da Turso."
        }
        return clean
    }
}

/** Un error de Turso o de la red. [code] es el código de Hrana o uno de los de aquí; el texto ya es para mostrarse. */
class HranaException(val code: String?, message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Se reduce la página a la mitad y se vuelve a pedir (`IsTooLargeOrSlow`). */
    val isTooLargeOrSlow: Boolean
        get() = code == TIMEOUT || code == RESPONSE_TOO_LARGE || code == HTTP_413 || message.orEmpty().contains("too large", ignoreCase = true)

    companion object {
        const val BLOCKED = "BLOCKED"
        const val ARCHIVED = "ARCHIVED"
        const val TIMEOUT = "TIMEOUT"
        const val NETWORK_ERROR = "NETWORK_ERROR"
        const val BAD_RESPONSE = "BAD_RESPONSE"
        const val RESPONSE_TOO_LARGE = "RESPONSE_TOO_LARGE"
        const val HTTP_413 = "HTTP_413"
        const val UNAUTHORIZED = "HTTP_401"

        /** HTTP 400 con "JWT" en el cuerpo: token mal copiado (V3). No es una rotación y no cuenta para borrarse (§4.7). */
        const val TOKEN_MALFORMED = "TOKEN_MALFORMED"

        const val QUOTA_MESSAGE = "Se agotó la cuota mensual de Turso. La nube no acepta lecturas hasta que empiece el siguiente mes o se " +
            "cambie de plan. Este equipo sigue con sus datos; la sincronización automática se reintenta cada hora."
        const val ARCHIVED_MESSAGE = "La base de Turso está archivada: el plan gratuito archiva las bases que pasan 10 días sin uso. " +
            "Reactívela desde la PC o el panel de Turso. Este equipo sigue con sus datos."
        const val TOKEN_REJECTED_MESSAGE = "Turso rechazó el token: se anuló o no es de esta base. Pegue el token nuevo; nada de aquí se pierde."
        const val TOKEN_MALFORMED_MESSAGE = "Turso no pudo leer el token: parece incompleto o mal copiado. Péguelo otra vez completo; nada de aquí se pierde."

        /** Turso no documenta el estado HTTP ni el texto de estos dos (V7, V9): se buscan en el código y en el texto. */
        private fun classify(code: String?, text: String?): String? = when {
            code.equals(BLOCKED, ignoreCase = true) -> BLOCKED
            text != null && (BLOCKED in text || text.contains("operation was blocked", ignoreCase = true)) -> BLOCKED
            text != null && text.contains("archived", ignoreCase = true) -> ARCHIVED
            code.equals(ARCHIVED, ignoreCase = true) -> ARCHIVED
            else -> null
        }

        fun fromResult(code: String?, message: String): HranaException = when (classify(code, message)) {
            BLOCKED -> HranaException(BLOCKED, QUOTA_MESSAGE)
            ARCHIVED -> HranaException(ARCHIVED, ARCHIVED_MESSAGE)
            else -> HranaException(code, message)
        }

        fun fromHttp(status: Int, body: String): HranaException {
            when (classify(null, body)) {
                BLOCKED -> return HranaException(BLOCKED, QUOTA_MESSAGE)
                ARCHIVED -> return HranaException(ARCHIVED, ARCHIVED_MESSAGE)
            }
            return when {
                status == 401 -> HranaException(UNAUTHORIZED, TOKEN_REJECTED_MESSAGE)
                status == 400 && body.contains("JWT", ignoreCase = true) -> HranaException(TOKEN_MALFORMED, TOKEN_MALFORMED_MESSAGE)
                status == 403 -> HranaException("HTTP_403", "Acceso denegado (HTTP 403): el token no tiene permisos para esta base.")
                status == 404 -> HranaException("HTTP_404", "Base o dirección no encontrada (HTTP 404): revise la URL.")
                else -> HranaException("HTTP_$status", "Turso respondió con error HTTP $status: ${body.take(300)}")
            }
        }
    }
}
