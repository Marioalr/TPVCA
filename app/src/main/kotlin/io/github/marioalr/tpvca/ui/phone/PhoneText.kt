package io.github.marioalr.tpvca.ui.phone

/** Los textos de las pantallas del celular, del diseño aprobado. La app es solo en español. */
object PhoneText {
    const val READ_ONLY = "Solo consulta"
    const val NEXT_VERSION = "En la siguiente versión"
    const val NEXT_VERSION_LOWER = "en la siguiente versión"
    const val LOADING = "Leyendo lo guardado en este equipo…"
    const val READ_ERROR = "No se pudo leer lo guardado en este equipo. No se borró nada: intente de nuevo."

    const val HINT_NEGOCIO = "Deslice hacia arriba para ver el negocio"
    const val HINT_INICIO = "Deslice hacia abajo para volver a Inicio"
    const val OPEN_NEGOCIO = "Abrir Negocio"
    const val BACK_INICIO = "Volver a Inicio"

    const val SCHEDULE = "Agendar cita"
    const val STOCK_ENTRY = "Entrada de mercancía"
    const val SEARCH_PATIENT = "Buscar paciente"
    const val CLOSE_SCHEDULE = "Cerrar sin agendar"
    const val CHECKING = "Revisando la agenda…"
    const val NO_PATIENT_MATCH = "Ningún paciente coincide con la búsqueda. El celular solo agenda a pacientes que ya existen: se dan de alta en la PC o en la tablet."
    const val NO_FREE_SLOTS = "Sin huecos libres este día. Elija otro."
    const val PAST_DAY = "Este día ya pasó."

    /** El hueco elegido de hoy ya empezó. */
    fun slotPast(time: String) = "El hueco de las $time ya pasó. Elija otro: se conservan el paciente, el motivo y la duración."
    const val QUICK_ACTIONS = "Acciones rápidas"
    const val CLOSE_QUICK_ACTIONS = "Cerrar acciones rápidas"
    const val CLOSE = "Cerrar"

    const val NO_NOTICES = "Sin avisos"
    const val NO_UPCOMING = "Sin citas pendientes"
    const val NOTE_PANEL = "Mismas cuentas que el panel «Negocio» de la PC. Las ventas canceladas no cuentan; la consulta no tiene costo propio y sus insumos cuentan cuando se cobran aparte."
    const val COPY_NOT_FOUND = "Este registro ya no está en este equipo."
    const val SHARE_FAILED = "No se pudo preparar el documento."
    const val SHARE_TITLE = "Compartir"
    const val PREVIEW_FAILED = "No se pudo abrir el documento aquí. Compartir manda el archivo completo."
    const val LAB_OPEN_FAILED = "No se pudo abrir el resultado. No se borró nada: intente de nuevo."
}
