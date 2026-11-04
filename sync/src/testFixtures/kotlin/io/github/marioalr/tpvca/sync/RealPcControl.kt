package io.github.marioalr.tpvca.sync

import io.github.marioalr.tpvca.sync.hrana.HranaClient
import io.github.marioalr.tpvca.sync.hrana.HranaConnection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URI

/**
 * Las rutas de control con que la PC hace los pasos de A con su motor real (`contracts/README.md`, «Cómo los corre Kotlin»), solo en `127.0.0.1`: `/new`, `/step`, `/approve`, `/anchor` y `/snapshot`. Esta prueba
 * corre solo con `-Ptpvc.control=http://127.0.0.1:puerto`, porque las rutas viven en TPVC.
 */
class RealPcControl(private val base: String) {
    private fun call(path: String, body: JsonObject = JsonObject(emptyMap())): JsonObject {
        val connection = URI(base + path).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 10_000
        connection.readTimeout = 120_000
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val ok = connection.responseCode in 200..299
        val text = (if (ok) connection.inputStream else connection.errorStream).use { it.readBytes().toString(Charsets.UTF_8) }
        check(ok) { "La PC respondió ${connection.responseCode} a $path: ${text.take(1500)}" }
        return Json.parseToJsonElement(text).jsonObject
    }

    /** Una nube vacía y una PC nueva. Regresa la conexión de un equipo con ese simulador. */
    fun newCloud(watch: JsonObject): HranaConnection {
        val url = call("/new", JsonObject(mapOf("watch" to watch))).getValue("url").jsonPrimitive.content
        return HranaClient(url, "token-de-prueba", "1.0.0")
    }

    fun step(step: JsonObject): JsonElement? = call("/step", step)["result"]?.takeUnless { it === JsonNull }

    fun approve(deviceId: String, code: String, role: String, name: String) {
        call("/approve", JsonObject(mapOf("deviceId" to JsonPrimitive(deviceId), "code" to JsonPrimitive(code), "role" to JsonPrimitive(role), "name" to JsonPrimitive(name))))
    }

    fun anchor(anchor: String, stamp: String): String =
        call("/anchor", JsonObject(mapOf("anchor" to JsonPrimitive(anchor), "stamp" to JsonPrimitive(stamp)))).getValue("stamp").jsonPrimitive.content

    fun snapshot(): JsonObject = call("/snapshot")
}
