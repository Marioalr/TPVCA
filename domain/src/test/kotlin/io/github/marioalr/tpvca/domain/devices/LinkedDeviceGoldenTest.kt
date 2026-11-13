package io.github.marioalr.tpvca.domain.devices

import io.github.marioalr.tpvca.domain.golden.GoldenFile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import java.time.Duration
import java.time.LocalDateTime
import org.junit.Assert.assertEquals

/** `linked-device.json`: los equipos vinculados vistos desde un equipo y a quién se le ofrece «Enviar a…» (`LinkedDevices`). */
class LinkedDeviceGoldenTest {
    private fun rows(array: JsonArray): List<Map<String, Any?>> = array.map { element ->
        element.jsonObject.mapValues { (_, v) ->
            when {
                v is JsonNull -> null
                v is JsonPrimitive && v.isString -> v.content
                v is JsonPrimitive -> v.content.toLongOrNull() ?: v.content
                else -> v.toString()
            }
        }
    }

    @Test
    fun losEquiposVinculadosSonLosDelVector() {
        GoldenFile.load("linked-device").run { case ->
            val input = case.input
            val now = LinkedDevices.parseUtc(case.requireString("serverNowUtc"))!!
            val rotated = case.string("tokensRotatedAtUtc")?.let(LinkedDevices::parseUtc)
            val devices = LinkedDevices.merge(
                rows(input.getValue("heartbeats").jsonArray),
                rows(input.getValue("registry").jsonArray),
                case.string("primaryDeviceId"),
                now,
                case.requireString("thisDeviceId"),
                rotated,
            )
            val targets = LinkedDevices.handoffTargets(devices, now).map { it.deviceId.lowercase() }.toSet()
            JsonArray(
                devices.map { d ->
                    buildJsonObject {
                        put("deviceId", d.deviceId)
                        put("deviceName", d.deviceName)
                        put("role", d.role?.name)
                        put("deviceCode", d.deviceCode)
                        put("isThisDevice", d.isThisDevice)
                        put("isPrimary", d.isPrimary)
                        put("isOnline", d.isOnline)
                        put("isAvailable", d.isAvailable)
                        put("isRevoked", d.isRevoked)
                        put("isPendingApproval", d.isPendingApproval)
                        put("mergesAsWholeRow", d.mergesAsWholeRow)
                        put("ignoresServiceSupplies", d.ignoresServiceSupplies)
                        put("tokensRotated", d.tokensRotatedAtUtc != null)
                        put("pendingCount", d.pendingCount)
                        put("seenText", d.seenText)
                        // «Sin conexión desde <hora local>» depende de la zona y del día: el vector no lo fija
                        put("statusText", d.statusText.takeUnless { it.startsWith("Sin conexión desde") })
                        put("handoffTarget", d.deviceId.lowercase() in targets)
                    }
                },
            )
        }
    }

    private fun stamp(value: LocalDateTime): String = "%04d-%02d-%02d %02d:%02d:%02d.0000000".format(value.year, value.monthValue, value.dayOfMonth, value.hour, value.minute, value.second)

    @Test
    fun sinConexionDesdeDiceHoyAyerOLaFecha() {
        val now = LocalDateTime.of(2026, 1, 15, 17, 0)
        fun status(ago: Duration): String {
            val row = mapOf<String, Any?>("DeviceId" to "pc", "DeviceName" to "PC", "DeviceRole" to "WindowsDesktop", "AppVersion" to "1.43.0", "HandoffProtocol" to 1L, "LastSeenUtc" to stamp(now.minus(ago)))
            return LinkedDevices.toDevice(row, now).statusText
        }
        assertEquals(true, status(Duration.ofHours(3)).startsWith("Sin conexión desde hoy "))
        assertEquals(true, status(Duration.ofHours(26)).startsWith("Sin conexión desde ayer "))
        assertEquals(true, status(Duration.ofDays(3)).startsWith("Sin conexión desde 12/01 "))
    }

    @Test
    fun enviarAOfreceEnLineaPrimero() {
        val now = LocalDateTime.of(2026, 1, 15, 17, 0)
        fun beat(id: String, ago: Duration) = mapOf<String, Any?>("DeviceId" to id, "DeviceName" to id, "DeviceRole" to "WindowsDesktop", "AppVersion" to "1.43.0", "HandoffProtocol" to 1L, "Platform" to "Windows", "LastSeenUtc" to stamp(now.minus(ago)))
        val devices = LinkedDevices.merge(listOf(beat("vieja", Duration.ofDays(2)), beat("yo", Duration.ofSeconds(1)), beat("nueva", Duration.ofSeconds(30))), emptyList(), null, now, "yo")
        assertEquals(listOf("nueva", "vieja"), LinkedDevices.sendTargets(devices, now).map { it.deviceId })
    }
}
