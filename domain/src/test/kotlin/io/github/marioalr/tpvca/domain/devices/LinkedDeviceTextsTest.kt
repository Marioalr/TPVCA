package io.github.marioalr.tpvca.domain.devices

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

/** Los textos de *Equipos vinculados* que no trae `linked-device.json` (`CodeText`, `PendingText`, `ApprovalText` y `NewTokenText` de `Models/ConsultationHandoff.cs`), con la hora del consultorio. */
class LinkedDeviceTextsTest {
    private fun device(role: DeviceRole? = DeviceRole.AndroidTablet, code: String? = null, pending: Int? = null, oldest: LocalDateTime? = null, rotated: LocalDateTime? = null, id: String = "tablet-3f9a2c") =
        LinkedDevice(id, "Galaxy Tab", role, "1.0.0", null, false, false, "", deviceCode = code, pendingCount = pending, oldestPendingUtc = oldest, tokensRotatedAtUtc = rotated)

    @Test
    fun codigoYPendientes() {
        assertEquals("Código T1", device(code = "T1").codeText)
        assertEquals("Sin código todavía", device(code = "").codeText)
        assertEquals("Cambios pendientes: su versión no los informa", device().pendingText)
        assertEquals("Sin cambios pendientes", device(pending = 0).pendingText)
        assertEquals("1 cambio sin subir", device(pending = 1).pendingText)
        // 15/01 16:30 UTC es 10:30 en el consultorio (UTC−6)
        assertEquals("1,234 cambios sin subir desde 15/01 10:30", device(pending = 1234, oldest = LocalDateTime.of(2026, 1, 15, 16, 30)).pendingText)
    }

    @Test
    fun aprobacionYTokenNuevo() {
        assertEquals("Pide entrar como tablet · verifique que la tablet muestre 3F9A2C", device().approvalText)
        assertEquals("Pide entrar como celular · verifique que el celular muestre ABC", device(DeviceRole.AndroidMobileAdmin, id = "abc").approvalText)
        assertEquals("", device().newTokenText)
        assertEquals(
            "Pegue el token nuevo en esa computadora: no se conecta desde que se anularon los tokens, el 15/01 10:30. No pierde sus cambios.",
            device(DeviceRole.WindowsDesktop, rotated = LocalDateTime.of(2026, 1, 15, 16, 30)).newTokenText,
        )
    }

    @Test
    fun laListaQuitaLosQuitadosYOrdenaComoLaPc() {
        val now = LocalDateTime.of(2026, 1, 15, 17, 0)
        val list = LinkedDevices.listed(
            listOf(
                device(id = "a").copy(lastSeenUtc = now.minusHours(2)),
                device(id = "b").copy(isOnline = true, lastSeenUtc = now),
                device(id = "c").copy(isRevoked = true, isOnline = true),
                device(id = "d").copy(isPrimary = true),
                device(id = "e").copy(isThisDevice = true),
                device(id = "f").copy(lastSeenUtc = now.minusHours(1)),
            ),
        )
        assertEquals(listOf("e", "d", "b", "f", "a"), list.map { it.deviceId })
    }
}
