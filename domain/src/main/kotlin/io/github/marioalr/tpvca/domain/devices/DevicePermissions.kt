package io.github.marioalr.tpvca.domain.devices

import io.github.marioalr.tpvca.domain.devices.DeviceRole.AndroidMobileAdmin
import io.github.marioalr.tpvca.domain.devices.DeviceRole.AndroidTablet
import io.github.marioalr.tpvca.domain.devices.DeviceRole.WindowsDesktop

/**
 * Matriz de permisos según el rol del equipo (plan de la Fase 8, §4.6), igual a `DevicePermissions` de
 * `Models/TursoConfig.cs`. Con el nivel C el token de Turso es completo: estos límites los hace cumplir la app, con el rol que
 * la PC le asignó en `DeviceRegistry`.
 *
 * Decisión del médico del 2026-10-22: la tablet es una copia exacta de la PC, con solo las adaptaciones táctiles. Tiene todo
 * lo que tiene la PC salvo las tareas de dueño de la nube ([canAdministerCloud]). El celular conserva su alcance.
 *
 * Cada permiso nuevo va también en la matriz de `DevicePermissionsTest`.
 */
object DevicePermissions {
    /** Ver expedientes (paso 8.10): todos los equipos; el celular, solo para consultar. */
    @JvmStatic
    fun canViewClinicalRecords(role: DeviceRole): Boolean = true

    @JvmStatic
    fun canManageClinicalRecords(role: DeviceRole): Boolean = role == WindowsDesktop || role == AndroidTablet

    @JvmStatic
    fun canPrescribeMedications(role: DeviceRole): Boolean = role == WindowsDesktop || role == AndroidTablet

    @JvmStatic
    fun canProcessSales(role: DeviceRole): Boolean = role == WindowsDesktop || role == AndroidTablet

    /**
     * Editar inventario y catálogo: productos (alta, precio, costo, existencias), categorías, servicios clínicos y sus insumos
     * por defecto, conteos y cerrar lotes. La PC y la tablet (desde el 2026-10-22); el celular solo lo ve.
     */
    @JvmStatic
    fun canManageInventory(role: DeviceRole): Boolean = role == WindowsDesktop || role == AndroidTablet

    /**
     * Registrar entradas de mercancía: un lote de un producto que ya existe con su movimiento de entrada (paso 8.10). Todos los
     * equipos. «Ingresar pedido» del proveedor pide además [canManageInventory].
     */
    @JvmStatic
    fun canRegisterStockEntries(role: DeviceRole): Boolean = true

    /** Agendar citas (paso 8.10): todos los equipos. */
    @JvmStatic
    fun canScheduleAppointments(role: DeviceRole): Boolean = true

    /** Compartir la receta y el ticket (paso 8.10): todos los equipos. */
    @JvmStatic
    fun canShareDocuments(role: DeviceRole): Boolean = true

    @JvmStatic
    fun canViewDashboard(role: DeviceRole): Boolean = true

    /** Corte de caja: todos los equipos. */
    @JvmStatic
    fun canViewCashCut(role: DeviceRole): Boolean = true

    @JvmStatic
    fun canViewStockAlerts(role: DeviceRole): Boolean = true

    /** Los reportes del negocio (panel «Negocio»): todos los equipos. */
    @JvmStatic
    fun canViewBusinessReports(role: DeviceRole): Boolean = true

    /**
     * Configuración del consultorio: médico y consultorio, membrete, servicios, plantillas de receta, dosis pediátricas e
     * importar el Excel, y las preferencias de este equipo. La PC y la tablet. Lo que es de la nube es [canAdministerCloud].
     */
    @JvmStatic
    fun canConfigureSystem(role: DeviceRole): Boolean = role == WindowsDesktop || role == AndroidTablet

    /**
     * Las tareas de dueño de la nube, solo la PC: el esquema remoto y `_tpvc_meta`, la sincronización completa, unirse o
     * recuperar como PC, reemplazar la nube o restaurar con la nube activa (cambian la generación), vincular, aprobar,
     * rechazar, quitar y «Perdí este equipo» (`DeviceRegistry`), los respaldos de la base y, en la PC principal, regularizar
     * folios, los saldos iniciales y archivar los resultados de laboratorio. La app de Android nunca los hace.
     */
    @JvmStatic
    fun canAdministerCloud(role: DeviceRole): Boolean = role == WindowsDesktop

    /** Recibe y envía la consulta en curso a otro equipo: el celular no. */
    @JvmStatic
    fun canTakeConsultations(role: DeviceRole): Boolean = role == WindowsDesktop || role == AndroidTablet

    /** La letra del código de cada equipo (P1, T1, C1…; paso 8.10). */
    @JvmStatic
    fun codeLetter(role: DeviceRole): Char = when (role) {
        AndroidTablet -> 'T'
        AndroidMobileAdmin -> 'C'
        WindowsDesktop -> 'P'
    }

    @JvmStatic
    fun roleDescription(role: DeviceRole): String = when (role) {
        WindowsDesktop -> "Acceso completo: consultas, recetas, cobro, inventario, configuración y la nube."
        AndroidTablet -> "Copia de la PC: consultas, recetas, cobro, inventario y configuración. La nube y los equipos se administran en la PC."
        AndroidMobileAdmin -> "Ve expedientes, agenda citas, revisa existencias, registra entradas de mercancía y ve el corte de caja y los reportes."
    }
}
