package io.github.marioalr.tpvca.sync

import io.github.marioalr.tpvca.sync.hrana.HranaClient
import io.github.marioalr.tpvca.sync.hrana.HranaException
import io.github.marioalr.tpvca.sync.hrana.HranaValue
import io.github.marioalr.tpvca.sync.hrana.PipelineUrl
import io.github.marioalr.tpvca.sync.hrana.Statement
import io.github.marioalr.tpvca.sync.hrana.text
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class HranaClientTest {
    private lateinit var server: MockWebServer

    private fun client(http: OkHttpClient = HranaClient.defaultHttp()) =
        HranaClient(server.url("/").toString(), "  \"Bearer token-de-prueba\"\n", "1.2.3", http)

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    private val empty = """{"baton":null,"results":[{"type":"ok","response":{"type":"execute","result":{"cols":[{"name":"n"}],"rows":[[{"type":"integer","value":"9007199254740993"}]],"affected_row_count":0,"rows_read":3,"query_duration_ms":1.5}}},{"type":"ok","response":{"type":"close"}}]}"""

    @Before
    fun start() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun stop() = server.close()

    @Test
    fun laPeticionEsLaDelPlan() {
        server.enqueue(ok(empty))
        val rows = client().query(listOf(Statement("SELECT ?", listOf(HranaValue.Integer(42), HranaValue.Text("ñ"), HranaValue.Null)))).single().rows

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v2/pipeline", request.path)
        assertEquals("Bearer token-de-prueba", request.getHeader("Authorization"))
        assertEquals("TPVC-Android/1.2.3", request.getHeader("User-Agent"))
        assertEquals(
            Json.parseToJsonElement(
                """{"requests":[{"type":"execute","stmt":{"sql":"SELECT ?","args":[{"type":"integer","value":"42"},{"type":"text","value":"ñ"},{"type":"null"}]}},{"type":"close"}]}""",
            ),
            Json.parseToJsonElement(request.body.readUtf8()),
        )
        // Más grande que 2^53: sigue siendo exacto. Las estadísticas que agrega Turso se ignoran
        assertEquals("9007199254740993", rows.single().text("N"))
    }

    @Test
    fun laFotoEsUnBatchConBeginYCommit() {
        server.enqueue(
            ok(
                """{"baton":null,"results":[
                {"type":"ok","response":{"type":"execute","result":{"cols":[{"name":"a"}],"rows":[[{"type":"text","value":"suelta"}]],"affected_row_count":0}}},
                {"type":"ok","response":{"type":"batch","result":{"step_results":[
                    {"cols":[],"rows":[],"affected_row_count":0},
                    {"cols":[{"name":"b"}],"rows":[[{"type":"float","value":0.1}]],"affected_row_count":0},
                    {"cols":[],"rows":[],"affected_row_count":0},null],"step_errors":[null,null,null,null]}}},
                {"type":"ok","response":{"type":"close"}}]}""",
            ),
        )
        val snapshot = client().snapshot(listOf(Statement("SELECT 1")), listOf(Statement("SELECT 2")))
        assertEquals("suelta", snapshot.before.single().rows.single().text("a"))
        assertEquals("0.1", snapshot.statements.single().rows.single().text("b"))
        assertEquals(
            Json.parseToJsonElement(
                """{"requests":[{"type":"execute","stmt":{"sql":"SELECT 1"}},{"type":"batch","batch":{"steps":[
                {"stmt":{"sql":"BEGIN"}},
                {"condition":{"type":"ok","step":0},"stmt":{"sql":"SELECT 2"}},
                {"condition":{"type":"ok","step":1},"stmt":{"sql":"COMMIT"}},
                {"condition":{"type":"not","cond":{"type":"ok","step":2}},"stmt":{"sql":"ROLLBACK"}}]}},{"type":"close"}]}""",
            ),
            Json.parseToJsonElement(server.takeRequest().body.readUtf8()),
        )
    }

    private fun failure(response: MockResponse): HranaException {
        server.enqueue(response)
        return assertThrows(HranaException::class.java) { client().query(listOf(Statement("SELECT 1"))) }
    }

    @Test
    fun cadaErrorTieneSuCodigo() {
        val error = """{"baton":null,"results":[{"type":"error","error":{"message":"%s","code":"%s"}},{"type":"ok","response":{"type":"close"}}]}"""
        assertEquals(HranaException.BLOCKED, failure(ok(error.format("Operation was blocked", "BLOCKED"))).code)
        assertEquals(HranaException.RESPONSE_TOO_LARGE, failure(ok(error.format("Respuesta enorme", "RESPONSE_TOO_LARGE"))).code)
        assertEquals("SQLITE_ERROR", failure(ok(error.format("no such table: _tpvc_meta", "SQLITE_ERROR"))).code)
        assertEquals(HranaException.BLOCKED, failure(MockResponse().setResponseCode(403).setBody("""{"error":"x","code":"BLOCKED"}""")).code)
        assertEquals(HranaException.ARCHIVED, failure(MockResponse().setResponseCode(400).setBody("""{"error":"database is archived"}""")).code)
        assertEquals(HranaException.UNAUTHORIZED, failure(MockResponse().setResponseCode(401).setBody("""{"error":"Unauthorized"}""")).code)
        assertEquals(HranaException.TOKEN_MALFORMED, failure(MockResponse().setResponseCode(400).setBody("""{"error":"JWT error: InvalidToken"}""")).code)
        assertEquals("HTTP_400", failure(MockResponse().setResponseCode(400).setBody("""{"error":"otra cosa"}""")).code)
        assertTrue(failure(MockResponse().setResponseCode(413).setBody("demasiado")).isTooLargeOrSlow)
        assertEquals(HranaException.BAD_RESPONSE, failure(ok("esto no es JSON")).code)
    }

    @Test
    fun elPrimerErrorDelBatchManda() {
        server.enqueue(
            ok(
                """{"baton":null,"results":[{"type":"ok","response":{"type":"batch","result":{"step_results":[{"cols":[],"rows":[],"affected_row_count":0},null,null,null],
                "step_errors":[null,{"message":"muy grande","code":"RESPONSE_TOO_LARGE"},null,null]}}},{"type":"ok","response":{"type":"close"}}]}""",
            ),
        )
        val e = assertThrows(HranaException::class.java) { client().snapshot(emptyList(), listOf(Statement("SELECT 1"))) }
        assertEquals(HranaException.RESPONSE_TOO_LARGE, e.code)
    }

    @Test
    fun unTiempoAgotadoEsTimeout() {
        server.enqueue(ok(empty).setBodyDelay(2, TimeUnit.SECONDS))
        val http = HranaClient.defaultHttp().newBuilder().callTimeout(200, TimeUnit.MILLISECONDS).build()
        val e = assertThrows(HranaException::class.java) { client(http).query(listOf(Statement("SELECT 1"))) }
        assertEquals(HranaException.TIMEOUT, e.code)
        assertTrue(e.isTooLargeOrSlow)
    }

    @Test
    fun sinRedEsNetworkError() {
        val port = server.port
        server.close()
        val e = assertThrows(HranaException::class.java) {
            HranaClient("http://127.0.0.1:$port", "t", "1.0.0").query(listOf(Statement("SELECT 1")))
        }
        assertEquals(HranaException.NETWORK_ERROR, e.code)
        server = MockWebServer().apply { start() }
    }

    @Test
    fun noSigueRedirecciones() {
        server.enqueue(MockResponse().setResponseCode(301).setHeader("Location", "http://otro.example/"))
        assertEquals("HTTP_301", assertThrows(HranaException::class.java) { client().query(listOf(Statement("SELECT 1"))) }.code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun soloHttps() {
        assertEquals("https://base-usuario.example.io/v2/pipeline", PipelineUrl.normalize("libsql://base-usuario.example.io"))
        assertEquals("https://base-usuario.example.io/v2/pipeline", PipelineUrl.normalize("https://base-usuario.example.io/"))
        assertEquals("https://base-usuario.example.io/v2/pipeline", PipelineUrl.normalize(" base-usuario.example.io?authToken=algo "))
        assertEquals("http://127.0.0.1:8787/v2/pipeline", PipelineUrl.normalize("http://127.0.0.1:8787"))
        assertThrows(IllegalArgumentException::class.java) { PipelineUrl.normalize("http://192.168.1.10:8080") }
        assertThrows(IllegalArgumentException::class.java) { PipelineUrl.normalize("http://base.example.io") }
        assertThrows(IllegalArgumentException::class.java) { PipelineUrl.normalize("solonombre") }
        assertThrows(IllegalArgumentException::class.java) { PipelineUrl.normalize("  ") }
    }

    @Test
    fun elTokenSeLimpia() {
        assertEquals("abc.def", HranaClient.cleanToken(" 'Bearer abc.def'\r\n"))
        assertFalse(HranaClient.cleanToken("abc").startsWith("Bearer"))
    }
}
