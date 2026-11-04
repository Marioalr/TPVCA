package io.github.marioalr.tpvca.sync.link

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

/** Lo que lleva el QR de la PC. [toString] no muestra el token ni la URL: esto no debe llegar a una bitácora por accidente. */
class LinkData(val url: String, val token: String, val clinicName: String) {
    override fun toString(): String = "LinkData(consultorio=$clinicName)"
}

/**
 * El texto del QR de la PC (paso 8.14, `Services/Pairing/DeviceLinkCode.cs`, vector `contracts/golden/link-qr.json`):
 * `tpvc-link:1:` + base64url sin relleno de `{"u":url,"t":token,"n":consultorio}` en UTF-8, con los acentos sin escapar. Lleva el token
 * completo: la protección es que la PC lo muestra solo a pedido y lo cierra a los 2 minutos.
 */
object LinkCode {
    const val PREFIX = "tpvc-link:"
    const val VERSION = 1
    private val HEAD = "$PREFIX$VERSION:"

    /** Lo escribe solo la PC: aquí sirve para comprobar el formato contra los vectores. */
    fun encode(url: String, token: String, clinicName: String): String {
        require(url.isNotBlank() && token.isNotBlank()) { "Faltan la URL o el token de la nube." }
        val json = buildJsonObject {
            put("u", url.trim())
            put("t", token.trim())
            put("n", clinicName.trim())
        }.toString()
        return HEAD + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(Charsets.UTF_8))
    }

    /** `null` si no es un código de TPVC, si es de otra versión o si está dañado: el equipo pide pegar la URL y el token a mano. */
    fun decode(text: String?): LinkData? {
        val clean = text?.trim() ?: return null
        if (!clean.startsWith(HEAD)) return null
        return try {
            // Como la PC: se acepta con o sin relleno
            val bytes = Base64.getDecoder().decode(padded(clean.substring(HEAD.length).replace('-', '+').replace('_', '/')))
            val json = Json.parseToJsonElement(String(bytes, Charsets.UTF_8)) as? JsonObject ?: return null
            val url = json.text("u")
            val token = json.text("t")
            if (url.isNullOrBlank() || token.isNullOrBlank()) null else LinkData(url, token, json.text("n").orEmpty())
        } catch (_: IllegalArgumentException) {
            // Base64 dañado o un JSON que no se puede leer (SerializationException es una IllegalArgumentException)
            null
        }
    }

    private fun padded(payload: String) = payload + "=".repeat((4 - payload.length % 4) % 4)

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
