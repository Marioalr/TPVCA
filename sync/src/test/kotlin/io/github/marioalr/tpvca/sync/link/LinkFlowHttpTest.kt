package io.github.marioalr.tpvca.sync.link

import io.github.marioalr.tpvca.domain.devices.DeviceRole
import io.github.marioalr.tpvca.sync.Contracts
import io.github.marioalr.tpvca.sync.InMemorySyncStore
import io.github.marioalr.tpvca.sync.MutableClock
import io.github.marioalr.tpvca.sync.Statements
import io.github.marioalr.tpvca.sync.SyncContract
import io.github.marioalr.tpvca.sync.engine.SyncStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.Base64

/**
 * Desde el texto del QR hasta el cable, con el cliente real: un servidor local responde como Turso a lo que manda [LinkFlow]. La PC no se
 * conecta con el equipo: se encuentran en la nube, así que lo que hay que comprobar es lo que el equipo escribe y lee ahí.
 */
class LinkFlowHttpTest {
    private lateinit var server: MockWebServer
    private val statements = ArrayList<JsonObject>()
    private val headers = ArrayList<String?>()
    private var registryRow: List<String?>? = null
    private var status: Pair<Int, String>? = null

    private val token = "eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9.eyJhIjoicnciLCJpYXQiOjE3NjAwMDAwMDB9.c2lnbmF0dXJhLWRlLWVqZW1wbG8"

    private val meta = mapOf(
        "schema" to SyncContract.bundledSchema.fingerprint,
        "clinicId" to "clinica-1",
        "generation" to "gen-1",
        "generationReady" to "gen-1",
        "protocol" to "3",
        "minClientProtocol" to "2",
        "fullUploadAtUtc" to "2026-10-01 10:00:00.000",
        "manifest" to SyncContract.bundledSchemaText(),
    )

    private fun text(value: String?) = if (value == null) """{"type":"null"}""" else """{"type":"text","value":${JsonPrimitive(value)}}"""

    private fun result(columns: List<String>, rows: List<List<String?>>, affected: Int = 0): String {
        val cols = columns.joinToString(",") { """{"name":"$it"}""" }
        val body = rows.joinToString(",") { row -> "[" + row.joinToString(",", transform = ::text) + "]" }
        return """{"type":"ok","response":{"type":"execute","result":{"cols":[$cols],"rows":[$body],"affected_row_count":$affected}}}"""
    }

    private fun answer(request: RecordedRequest): MockResponse {
        status?.let { (code, body) -> return MockResponse().setResponseCode(code).setBody(body) }
        headers += request.getHeader("Authorization")
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        val results = body.getValue("requests").jsonArray.map { it.jsonObject }.map { item ->
            when (item.getValue("type").jsonPrimitive.content) {
                "close" -> """{"type":"ok","response":{"type":"close"}}"""
                else -> {
                    val stmt = item.getValue("stmt").jsonObject
                    statements += stmt
                    when (stmt.getValue("sql").jsonPrimitive.content) {
                        Statements.META_ALL -> result(listOf("Key", "Value"), meta.map { listOf(it.key, it.value) })
                        Statements.REGISTRY_OWN -> result(listOf("DeviceCode", "Role", "RevokedAtUtc", "RevokedReason"), listOfNotNull(registryRow))
                        Statements.HEARTBEAT_READ_NONCE -> result(listOf("InstanceNonce"), emptyList())
                        Statements.HEARTBEAT_WRITE -> result(emptyList(), emptyList(), affected = 1)
                        else -> error("Fuera de lo que se espera antes de aprobar: ${stmt.getValue("sql")}")
                    }
                }
            }
        }
        return MockResponse().setResponseCode(200).setBody("""{"baton":null,"results":[${results.joinToString(",")}]}""")
    }

    @Before
    fun start() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = answer(request)
            }
            start()
        }
    }

    @After
    fun stop() = server.close()

    private val database = object : LocalDatabase {
        val current = InMemorySyncStore()

        override fun store(): SyncStore = current

        override fun erase() = Unit
    }

    private val vault = object : CredentialVault {
        var value: StoredCredential = StoredCredential.Empty

        override fun load() = value

        override fun save(credential: DeviceCredential) {
            value = StoredCredential.Present(credential)
        }

        override fun clear() {
            value = StoredCredential.Empty
        }
    }

    private val notices = object : LinkNotices {
        var value: Revocation? = null

        override fun revocation() = value

        override fun saveRevocation(reason: String?) {
            value = Revocation(reason)
        }

        override fun clearRevocation() {
            value = null
        }
    }

    private fun flow() = LinkFlow(
        vault,
        database,
        notices,
        { true },
        "0.1.0",
        "Galaxy Tab S9",
        {},
        clock = MutableClock(Instant.parse("2026-11-03T18:00:00Z")),
        newId = { "android-tab-3f9a2c" },
        newNonce = { "nonce-de-prueba" },
    )

    /** Un QR con el formato de la PC, pero que apunta a este servidor local. */
    private fun qr(): LinkData {
        val json = """{"u":"${server.url("/").toString().trimEnd('/')}","t":"$token","n":"Consultorio Médico Peña"}"""
        return LinkCode.decode("tpvc-link:1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray()))!!
    }

    @Test
    fun elQrLlegaHastaElLatidoEnElCable() {
        val data = qr()
        val state = flow().link(data.url, data.token, data.clinicName, DeviceRole.AndroidTablet)

        assertEquals(LinkState.WaitingApproval("3F9A2C", DeviceRole.AndroidTablet, "Consultorio Médico Peña"), state)
        assertTrue(headers.all { it == "Bearer $token" })
        assertEquals(
            listOf(Statements.META_ALL, Statements.REGISTRY_OWN, Statements.HEARTBEAT_READ_NONCE, Statements.HEARTBEAT_WRITE),
            statements.map { it.getValue("sql").jsonPrimitive.content },
        )
        val args = statements.last().getValue("args").jsonArray.map { it.jsonObject }
        assertEquals(11, args.size)
        fun value(i: Int) = args[i]["value"]?.jsonPrimitive?.content
        assertEquals("android-tab-3f9a2c", value(0))
        assertEquals("Galaxy Tab S9", value(1))
        assertEquals("AndroidTablet", value(2))
        assertEquals("0.1.0", value(3))
        assertEquals("Android", value(4))
        assertEquals("integer", args[5]["type"]?.jsonPrimitive?.content)
        assertEquals("null", args[9]["type"]?.jsonPrimitive?.content)
        assertEquals("nonce-de-prueba", value(10))
    }

    @Test
    fun rechazadoEnElCableBorraYNoSigue() {
        val data = qr()
        val flow = flow()
        flow.link(data.url, data.token, data.clinicName, DeviceRole.AndroidTablet)
        registryRow = listOf("X1", "AndroidTablet", "2026-11-03 18:00:00.000", "rechazado")
        assertEquals(LinkState.Revoked("rechazado", erased = true), flow.refresh(Refresh.USER))
        assertEquals(StoredCredential.Empty, vault.value)
    }

    /** El cuerpo del 401 es el de la grabación de la PC (`contracts/hrana/unauthorized.jsonl`). */
    @Test
    fun el401GrabadoEsUnTokenRechazado() {
        val recorded = Contracts.file("hrana/unauthorized.jsonl").readLines().map { Json.parseToJsonElement(it).jsonObject }
            .first { "response" in it }
        status = recorded.getValue("status").jsonPrimitive.content.toInt() to recorded.getValue("response").toString()
        val data = qr()
        assertEquals(LinkState.NeedsToken(malformed = false, erased = false, pending = 0), flow().link(data.url, data.token, "", DeviceRole.AndroidMobileAdmin))
    }

    @Test
    fun el400DeV3EsUnTokenMalCopiado() {
        status = 400 to """{"error":"JWT error: InvalidToken"}"""
        val data = qr()
        assertEquals(LinkState.NeedsToken(malformed = true, erased = false, pending = 0), flow().link(data.url, data.token, "", DeviceRole.AndroidMobileAdmin))
        assertFalse(database.current.metaValues.toString(), database.current.metaValues.toString().contains(token))
    }
}
