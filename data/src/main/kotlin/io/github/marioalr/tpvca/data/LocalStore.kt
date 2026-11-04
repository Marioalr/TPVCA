package io.github.marioalr.tpvca.data

/**
 * La base local de este equipo. Reglas que no cambian:
 * - empieza vacía y baja todo de la nube: nunca siembra datos;
 * - llaves por `SyncId`, sin llaves foráneas;
 * - la llave de SQLCipher, envuelta por el Keystore, es de este equipo y nunca viaja ([SqlCipher], `EncryptedLocalDatabase`).
 */
object LocalStore {
    /** Nombre del archivo de la base. */
    const val DATABASE_NAME: String = "tpvca.db"
}
