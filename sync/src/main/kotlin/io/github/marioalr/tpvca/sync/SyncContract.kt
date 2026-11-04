package io.github.marioalr.tpvca.sync

/**
 * Lo que esta app entiende del contrato de sincronización de la PC (`contracts/sync/schema.json`).
 *
 * Al traer contratos nuevos con `scripts/sync-contracts.sh`, la prueba `SyncContractTest` falla hasta que [REVISION] se sube a
 * mano: así un cambio del contrato nunca llega a la app sin que alguien lo revise.
 */
object SyncContract {
    /** La revisión del contrato que implementa esta versión de la app (`SyncContract.Revision` en la PC). */
    const val REVISION: Int = 22

    /**
     * Desde esta revisión el formato está congelado: solo admite cambios aditivos (`SyncContract.FrozenRevision` en la PC).
     * Es la base de la tablet.
     */
    const val FROZEN_REVISION: Int = 13

    /**
     * El protocolo que habla esta app (`ClientProtocol` en la PC): el 2 con la cerca de generación (8.9). Si
     * `_tpvc_meta.minClientProtocol` es mayor, la sincronización se detiene y pide actualizar.
     */
    const val CLIENT_PROTOCOL: Int = 3

    /** El manifiesto incluido en la app: el mismo `contracts/sync/schema.json` (lo copia `processResources`). */
    private const val BUNDLED_SCHEMA = "/tpvca/contracts/schema.json"

    fun bundledSchemaText(): String =
        SyncContract::class.java.getResourceAsStream(BUNDLED_SCHEMA)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("Falta $BUNDLED_SCHEMA en la app")

    val bundledSchema: SyncSchema by lazy { SyncSchema.parse(bundledSchemaText()) }

    /** Llave de todas las tablas sincronizadas: GUID en minúsculas con guiones. */
    const val PRIMARY_KEY: String = "SyncId"
}
