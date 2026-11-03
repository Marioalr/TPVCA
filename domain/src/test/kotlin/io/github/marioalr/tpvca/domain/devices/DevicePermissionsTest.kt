package io.github.marioalr.tpvca.domain.devices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * La matriz completa de §4.6 del plan (2026-10-22), igual a `DevicePermissions_TheTabletIsACopyOfThePc_ExceptAdministeringTheCloud`
 * de la PC (`tests/TPVC.Tests/TursoTests.cs`): PC, tablet y celular, en ese orden.
 */
class DevicePermissionsTest {
    private class Row(val name: String, val rule: (DeviceRole) -> Boolean, val pc: Boolean, val tablet: Boolean, val phone: Boolean)

    private val matrix = listOf(
        Row("canViewClinicalRecords", DevicePermissions::canViewClinicalRecords, pc = true, tablet = true, phone = true),
        Row("canManageClinicalRecords", DevicePermissions::canManageClinicalRecords, pc = true, tablet = true, phone = false),
        Row("canPrescribeMedications", DevicePermissions::canPrescribeMedications, pc = true, tablet = true, phone = false),
        Row("canProcessSales", DevicePermissions::canProcessSales, pc = true, tablet = true, phone = false),
        Row("canManageInventory", DevicePermissions::canManageInventory, pc = true, tablet = true, phone = false),
        Row("canRegisterStockEntries", DevicePermissions::canRegisterStockEntries, pc = true, tablet = true, phone = true),
        Row("canScheduleAppointments", DevicePermissions::canScheduleAppointments, pc = true, tablet = true, phone = true),
        Row("canShareDocuments", DevicePermissions::canShareDocuments, pc = true, tablet = true, phone = true),
        Row("canViewDashboard", DevicePermissions::canViewDashboard, pc = true, tablet = true, phone = true),
        Row("canViewCashCut", DevicePermissions::canViewCashCut, pc = true, tablet = true, phone = true),
        Row("canViewStockAlerts", DevicePermissions::canViewStockAlerts, pc = true, tablet = true, phone = true),
        Row("canViewBusinessReports", DevicePermissions::canViewBusinessReports, pc = true, tablet = true, phone = true),
        Row("canConfigureSystem", DevicePermissions::canConfigureSystem, pc = true, tablet = true, phone = false),
        Row("canTakeConsultations", DevicePermissions::canTakeConsultations, pc = true, tablet = true, phone = false),
        Row("canAdministerCloud", DevicePermissions::canAdministerCloud, pc = true, tablet = false, phone = false),
    )

    @Test
    fun laTabletEsUnaCopiaDeLaPcSalvoAdministrarLaNube() {
        // Cada permiso de DevicePermissions está en la matriz (uno nuevo obliga a decidir su fila)
        val declared = DevicePermissions::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && Modifier.isStatic(it.modifiers) }
            .filter { it.returnType == Boolean::class.javaPrimitiveType && it.parameterTypes.contentEquals(arrayOf(DeviceRole::class.java)) }
            .map { it.name }
            .sorted()
        assertEquals(declared, matrix.map { it.name }.sorted())

        for (row in matrix) {
            assertEquals("${row.name} en la PC", row.pc, row.rule(DeviceRole.WindowsDesktop))
            assertEquals("${row.name} en la tablet", row.tablet, row.rule(DeviceRole.AndroidTablet))
            assertEquals("${row.name} en el celular", row.phone, row.rule(DeviceRole.AndroidMobileAdmin))
            // La tablet es una copia de la PC: solo administrar la nube la distingue
            if (row.name != "canAdministerCloud") assertEquals("${row.name}: la tablet es una copia de la PC", row.pc, row.tablet)
            // El celular nunca tiene algo que la tablet no tenga
            if (row.phone) assertTrue("${row.name}: el celular no rebasa a la tablet", row.tablet)
        }
    }

    @Test
    fun ningunEquipoAndroidAdministraLaNube() {
        DeviceRole.entries.filter { it != DeviceRole.WindowsDesktop }.forEach {
            assertEquals(it.name, false, DevicePermissions.canAdministerCloud(it))
        }
    }

    @Test
    fun losNumerosYLetrasDeLosRolesSonLosDeLaPc() {
        assertEquals(listOf(1, 2, 3), DeviceRole.entries.map { it.code })
        assertEquals(DeviceRole.AndroidTablet, DeviceRole.fromCode(2))
        assertNull(DeviceRole.fromCode(9))
        assertEquals('P', DevicePermissions.codeLetter(DeviceRole.WindowsDesktop))
        assertEquals('T', DevicePermissions.codeLetter(DeviceRole.AndroidTablet))
        assertEquals('C', DevicePermissions.codeLetter(DeviceRole.AndroidMobileAdmin))
        DeviceRole.entries.forEach { assertTrue(DevicePermissions.roleDescription(it).isNotBlank()) }
    }
}
