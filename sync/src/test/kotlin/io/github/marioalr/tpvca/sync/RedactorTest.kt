package io.github.marioalr.tpvca.sync

import io.github.marioalr.tpvca.sync.hrana.HranaClient
import io.github.marioalr.tpvca.sync.hrana.HranaException
import io.github.marioalr.tpvca.sync.hrana.PipelineUrl
import io.github.marioalr.tpvca.sync.hrana.Redactor
import io.github.marioalr.tpvca.sync.hrana.Statement
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException

/** Los valores son los de ejemplo de `golden/link-qr.json`: los únicos que deja pasar `scripts/check-secrets.sh`. */
class RedactorTest {
    private val token = "eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9.eyJhIjoicnciLCJpYXQiOjE3NjAwMDAwMDB9.c2lnbmF0dXJhLWRlLWVqZW1wbG8"
    private val url = "libsql://consultorio-ejemplo.aws-us-east-2.turso.io"
    private val host = "consultorio-ejemplo.aws-us-east-2.turso.io"

    private fun assertClean(text: String?) {
        for (secret in listOf(token, url, host, "signatura", "consultorio-ejemplo")) assertFalse("$secret en: $text", text.orEmpty().contains(secret))
    }

    @Test
    fun quitaLoQueTieneFormaDeSecreto() {
        assertClean(Redactor.clean("Unable to resolve host \"$host\": No address"))
        assertClean(Redactor.clean("Fallo $url con Authorization: Bearer $token"))
        assertClean(Redactor.clean("https://$host/v2/pipeline respondió"))
        assertClean(Redactor.clean(token))
    }

    @Test
    fun quitaLoQueSeLePasaAunqueNoTengaEsaForma() {
        assertEquals("el token es [oculto].", Redactor.clean("el token es abc-123.", listOf("abc-123")))
    }

    @Test
    fun loDemasSeQuedaIgual() {
        assertEquals("HTTP 500: error interno", Redactor.clean("HTTP 500: error interno", listOf(" ")))
    }

    @Test
    fun unErrorDelServidorQueRepiteElTokenNoLoRepiteAqui() {
        MockWebServer().use { server ->
            server.start()
            val base = server.url("/").toString().trimEnd('/')
            val client = HranaClient(base, "token-sin-forma-de-jwt", "1.0.0")
            server.enqueue(MockResponse().setResponseCode(500).setBody("fallo con token-sin-forma-de-jwt en $base y $url"))
            val http = assertThrows(HranaException::class.java) { client.query(listOf(Statement("SELECT 1"))) }
            assertClean(http.message)
            assertFalse(http.message!!, http.message!!.contains("token-sin-forma-de-jwt"))
            assertFalse(http.message!!, http.message!!.contains(base))

            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"results":[{"type":"error","error":{"message":"bad $token at $url"}}]}"""))
            val result = assertThrows(HranaException::class.java) { client.query(listOf(Statement("SELECT 1"))) }
            assertClean(result.message)
        }
    }

    @Test
    fun unErrorDeLaRedNoDiceAQueHostIba() {
        val closed = MockWebServer().apply { start() }
        val base = closed.url("/").toString().trimEnd('/')
        closed.close()
        val error = assertThrows(HranaException::class.java) { HranaClient(base, token, "1.0.0").query(listOf(Statement("SELECT 1"))) }
        assertEquals(HranaException.NETWORK_ERROR, error.code)
        assertClean(error.message)
        assertFalse(error.message!!, error.message!!.contains(base))
        assertNull("La excepción de la red traería el host en su texto", error.cause)
    }

    /** Lo que dice Android cuando no hay DNS, visto en un emulador: el texto de la excepción nombra al host, que no parece de Turso. */
    @Test
    fun unHostQueNoResuelveNoSaleEnElMensaje() {
        val host = "consultorio-ejemplo.turso.io"
        val http = OkHttpClient.Builder().dns { throw UnknownHostException("Unable to resolve host \"$it\": No address associated with hostname") }.build()
        val error = assertThrows(HranaException::class.java) { HranaClient("https://$host", token, "1.0.0", http).query(listOf(Statement("SELECT 1"))) }
        assertEquals(HranaException.NETWORK_ERROR, error.code)
        assertFalse(error.message!!, error.message!!.contains(host))
        assertFalse("Sin el inglés del sistema: ${error.message}", error.message!!.contains("Unable"))
    }

    @Test
    fun unErrorDelServidorQueNombraSoloElHostTampocoLoRepite() {
        MockWebServer().use { server ->
            server.start()
            val base = server.url("/").toString().trimEnd('/')
            server.enqueue(MockResponse().setResponseCode(500).setBody("fallo en ${server.hostName}:${server.port}"))
            val error = assertThrows(HranaException::class.java) { HranaClient(base, "t", "1.0.0").query(listOf(Statement("SELECT 1"))) }
            assertFalse(error.message!!, error.message!!.contains(server.hostName))
        }
    }

    @Test
    fun soloSeAceptanHostsDeTurso() {
        assertEquals("https://consultorio-ejemplo.turso.io/v2/pipeline", PipelineUrl.forDevice("https://consultorio-ejemplo.turso.io"))
        assertEquals("https://consultorio-ejemplo.aws-us-east-2.turso.io/v2/pipeline", PipelineUrl.forDevice(url))
        assertEquals("consultorio-ejemplo.turso.io", PipelineUrl.host("HTTPS://Consultorio-Ejemplo.Turso.io"))
        for (bad in listOf("https://evil.example", "https://consultorio-ejemplo.turso.io.evil.example", "https://tur" + "so.io.evil.example/x", "https://evil.example/.turso.io")) {
            assertThrows(bad, IllegalArgumentException::class.java) { PipelineUrl.normalize(bad) }
        }
        // El servidor de pruebas: la clase lo acepta, la app no
        assertEquals("http://127.0.0.1:8787/v2/pipeline", PipelineUrl.normalize("http://127.0.0.1:8787"))
        assertThrows(IllegalArgumentException::class.java) { PipelineUrl.forDevice("http://127.0.0.1:8787") }
        assertThrows(IllegalArgumentException::class.java) { PipelineUrl.forDevice("https://localhost:8443") }
    }

    @Test
    fun unTokenConCaracteresImposiblesEsMalCopiadoNoUnaCaida() {
        MockWebServer().use { server ->
            server.start()
            val error = assertThrows(HranaException::class.java) {
                HranaClient(server.url("/").toString(), "tokeñ", "1.0.0").query(listOf(Statement("SELECT 1")))
            }
            assertEquals(HranaException.TOKEN_MALFORMED, error.code)
        }
        assertFalse(HranaClient.isValidToken("tokeñ"))
        assertEquals("ab", HranaClient.cleanToken("\u00A0a\u200Bb\uFEFF"))
    }

    @Test
    fun unaUrlMalaNoRepiteLoPegado() {
        val error = assertThrows(IllegalArgumentException::class.java) { PipelineUrl.normalize("https://con espacio/$token") }
        assertClean(error.message)
        val soloNombre = assertThrows(IllegalArgumentException::class.java) { PipelineUrl.normalize("token-pegado-por-error") }
        assertFalse(soloNombre.message!!, soloNombre.message!!.contains("token-pegado-por-error"))
    }

    @Test
    fun unaUrlConUsuarioOContrasenaNoSeAcepta() {
        val error = assertThrows(IllegalArgumentException::class.java) { PipelineUrl.normalize("https://usuario:clave@$host") }
        assertTrue(error.message!!.contains("usuario"))
        assertFalse(error.message!!.contains("clave@"))
    }
}
