package io.github.marioalr.tpvca.sync.engine

/** Una fila en su forma local: `Long`, `Double`, `String`, `ByteArray` o `null`, por el nombre de la columna. */
typealias LocalRow = Map<String, Any?>

/** Una fila que se escribe. Con [isNew] lleva todas las columnas; si no, solo las que cambian (nunca las derivadas). */
data class RowWrite(val table: String, val syncId: String, val values: LocalRow, val isNew: Boolean)

/** Una referencia `setNull` cuyo principal se borró. */
data class Nullify(val table: String, val column: String, val syncIds: Set<String>)

/** Una lápida guardada aquí: dice qué principales se borraron. Con la misma llave, se queda la marca más nueva. */
data class TombstoneRow(
    val entityType: String,
    val syncId: String,
    val deletedAtUtc: String,
    val syncedAtUtc: String,
    val originDeviceId: String?,
)

enum class ParkDirection { PULL, TOMBSTONE, PUSH }

/**
 * Una fila de la cola de salida (plan §7.5, K5): qué registro cambió aquí y todavía no sube. [seq] viene de una secuencia local que solo sube y no depende del reloj;
 * si el registro se vuelve a editar, su entrada toma otro número. [protocol] y [contract] son con los que se escribió: si la nube llega a exigir otro, la cola se conserva.
 */
data class OutboxEntry(val seq: Long, val entityType: String, val syncId: String, val protocol: Int, val contract: Int, val enqueuedAtUtc: String)

/** Anotar un registro en la cola, en la misma transacción que su escritura. */
data class OutboxMark(val entityType: String, val syncId: String, val enqueuedAtUtc: String)

/**
 * Un cambio guardado para «Volver a aplicar» (`SyncSavedChanges` de la PC): [payload] es la fila como viaja (`RowMapper.serialize`) o, en un borrado, su marca. Es el mismo formato
 * que el archivo que exporta un equipo quitado, que la PC puede importar.
 */
data class SavedChange(
    val id: Long = 0,
    val source: String,
    val generation: String?,
    val entityType: String,
    val syncId: String,
    val kind: String,
    val payload: String,
    val protocol: Int,
    val contract: Int,
    val savedAtUtc: String,
) {
    companion object {
        const val SOURCE_THIS_DEVICE = "este-equipo"
        const val KIND_ROW = "row"
        const val KIND_TOMBSTONE = "tombstone"
    }
}

/** Una fila apartada (paso 8.4): [payload] es la fila remota tal como llegó, o la marca del borrado en una lápida. */
data class ParkedRow(
    val direction: ParkDirection,
    val entityType: String,
    val syncId: String,
    val payload: String,
    val reason: String,
)

/** Todo lo que se aplica junto en una transacción: una página y su cursor nunca quedan a medias. */
data class StoreChanges(
    val writes: List<RowWrite> = emptyList(),
    val deletes: Map<String, Set<String>> = emptyMap(),
    val nullify: List<Nullify> = emptyList(),
    val tombstones: List<TombstoneRow> = emptyList(),
    val park: List<ParkedRow> = emptyList(),
    /** Se quitan de las apartadas: (dirección, entityType, SyncId). */
    val unpark: List<Triple<ParkDirection, String, String>> = emptyList(),
    val meta: Map<String, String> = emptyMap(),
    val metaRemoved: Set<String> = emptySet(),
    /** Se anotan en la cola de salida: junto con la escritura o no se anotan. */
    val outbox: List<OutboxMark> = emptyList(),
    /** Ya subió: salen de la cola las entradas hasta este número. Lo que se editó después tiene un número mayor y se queda. */
    val outboxClearedUpTo: Long? = null,
    val saved: List<SavedChange> = emptyList(),
    /** Los [SavedChange.id] que ya se volvieron a aplicar. */
    val savedRemoved: List<Long> = emptyList(),
)

/**
 * La base local, vista por el motor. En la app es Room (`RoomSyncStore`, módulo `:data`); en las pruebas, un doble en memoria. Las
 * llaves son los `SyncId` en minúsculas, sin llaves foráneas: lo que haría una llave foránea lo decide el motor con `schema.json`.
 */
interface SyncStore {
    fun meta(key: String): String?

    fun metaByPrefix(prefix: String): Map<String, String>

    /** Aplica todo en una transacción. */
    fun apply(changes: StoreChanges)

    /**
     * Corre [block] en una transacción de escritura: leer y luego escribir no se cruza con otra escritura. Una excepción deshace todo. Se puede anidar. Es lo que evita que una bajada
     * pise una edición que se guardó entre leer las marcas y aplicar la página.
     */
    fun <T> transaction(block: () -> T): T

    /** `SyncId` → `UpdatedAtUtc` de las filas que existen aquí. */
    fun stamps(table: String, syncIds: Collection<String>): Map<String, String>

    /** Cuáles de estos `SyncId` existen aquí. */
    fun existing(table: String, syncIds: Collection<String>): Set<String>

    /** Los `SyncId` de las filas de [table] cuya [column] apunta a uno de [principals]. */
    fun referencing(table: String, column: String, principals: Collection<String>): Set<String>

    /** Las columnas pedidas; con [filterColumn], solo las filas cuyo valor está en [filterValues]. */
    fun select(table: String, columns: List<String>, filterColumn: String? = null, filterValues: Collection<String> = emptyList()): List<LocalRow>

    fun parkedRows(): List<ParkedRow>

    /** Cuáles de estos `SyncId` tienen lápida aquí. */
    fun tombstoned(entityType: String, syncIds: Collection<String>): Set<String>

    /** Marcas más de 2 min adelante de la hora del servidor (paso 8.7), también las de las lápidas: a [stamp], sin anotar nada para subir. Regresa cuántas. */
    fun repairFutureStamps(tables: List<String>, limit: String, stamp: String): Int

    /** La marca del perfil del consultorio, o `null` si no hay. */
    fun profileStamp(): String?

    fun saveProfile(values: LocalRow)

    fun hasAsset(hash: String): Boolean

    /** Guarda una imagen del membrete ya revisada (tamaño y SHA-256). */
    fun saveAsset(hash: String, kind: String, bytes: ByteArray)

    /** La cola de salida, en orden. */
    fun outbox(): List<OutboxEntry>

    /** Cambios sin subir: registros distintos en la cola o apartados al subir, como `PendingCount` del latido. */
    fun pendingChanges(): Int

    /** Cuándo se anotó lo más viejo que sigue sin subir (`OldestPendingUtc` del latido). */
    fun oldestPendingUtc(): String?

    /** Lo guardado para «Volver a aplicar»: lo que no subió antes de que la nube cambiara de generación. No se borra al vaciar. */
    fun savedChanges(): List<SavedChange>

    /**
     * Vacía los datos sincronizados y las claves de `meta`, salvo las que empiezan con [keepMetaPrefixes] (cambio de generación). **Con algo en la cola de salida lanza
     * `IllegalStateException`:** primero se guarda (`SavedChange`). Así ninguna ruta pierde un cambio sin darse cuenta. Lo guardado se queda.
     */
    fun clearSyncedData(keepMetaPrefixes: List<String>)

    /** Borra todo lo de este equipo (§4.7). La credencial la borra quien recibe el resultado (K3). */
    fun wipeEverything()

    /** Tabla → filas, para el estado y la pantalla de desarrollo. */
    fun rowCounts(): Map<String, Long>
}

/** Lo que aún no está en la nube y no se puede volver a bajar: la cola, lo apartado y lo guardado. Con algo aquí no se borra nada salvo un equipo perdido. */
fun SyncStore.unsyncedChanges(): Int = pendingChanges() + savedChanges().size
