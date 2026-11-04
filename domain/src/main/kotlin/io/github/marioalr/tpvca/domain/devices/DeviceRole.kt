package io.github.marioalr.tpvca.domain.devices

/**
 * Rol de cada equipo (`DeviceRole` de `Models/TursoConfig.cs` en la PC). En `DeviceRegistry.Role` y en el `DeviceRole` del latido
 * viaja el **nombre** del enum, así que los nombres no se cambian. [code] es el número del enum en C#: no viaja.
 */
enum class DeviceRole(val code: Int) {
    /** PC con Windows 11. Acceso completo; solo ella administra la nube ([DevicePermissions.canAdministerCloud]). */
    WindowsDesktop(1),

    /** Tablet Android: lo mismo que la PC (consulta, cobro, inventario y configuración) salvo administrar la nube. */
    AndroidTablet(2),

    /** Celular Android: pacientes para consultar, citas, entradas de mercancía, existencias y reportes. */
    AndroidMobileAdmin(3),
    ;

    companion object {
        /** El rol con ese número, o `null` si no existe. */
        fun fromCode(code: Int): DeviceRole? = entries.firstOrNull { it.code == code }

        /** El rol con ese nombre, como viaja en la nube, o `null` si la PC escribió uno que esta versión no conoce. */
        fun fromName(name: String?): DeviceRole? = entries.firstOrNull { it.name == name }
    }
}
