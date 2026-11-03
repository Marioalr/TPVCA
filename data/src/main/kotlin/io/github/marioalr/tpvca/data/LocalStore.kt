package io.github.marioalr.tpvca.data

/**
 * La base local de este equipo (Room + SQLCipher desde K2). Reglas que no cambian:
 * - empieza vacía y baja todo de la nube: nunca siembra datos;
 * - llaves por `SyncId`, sin llaves foráneas;
 * - la llave de SQLCipher vive en el Keystore y nunca viaja.
 */
object LocalStore {
    /** Nombre del archivo de la base (todavía no se crea). */
    const val DATABASE_NAME: String = "tpvca.db"
}
