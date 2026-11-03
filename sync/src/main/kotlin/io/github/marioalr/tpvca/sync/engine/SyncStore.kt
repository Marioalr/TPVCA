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

enum class ParkDirection { PULL, TOMBSTONE }

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

    /** Marcas más de 2 min adelante de la hora del servidor (paso 8.7): a [stamp], sin anotar nada para subir. */
    fun repairFutureStamps(tables: List<String>, limit: String, stamp: String)

    /** La marca del perfil del consultorio, o `null` si no hay. */
    fun profileStamp(): String?

    fun saveProfile(values: LocalRow)

    fun hasAsset(hash: String): Boolean

    /** Guarda una imagen del membrete ya revisada (tamaño y SHA-256). */
    fun saveAsset(hash: String, kind: String, bytes: ByteArray)

    /**
     * Cambios sin subir. Siempre 0 hasta K5, que trae la cola de salida; las reglas del 401 y de la revocación ya lo consultan para no
     * borrar nada pendiente.
     */
    fun pendingChanges(): Int

    /** Vacía los datos sincronizados y las claves de `meta`, salvo las que empiezan con [keepMetaPrefixes] (cambio de generación). */
    fun clearSyncedData(keepMetaPrefixes: List<String>)

    /** Borra todo lo de este equipo (§4.7). La credencial la borra quien recibe el resultado (K3). */
    fun wipeEverything()

    /** Tabla → filas, para el estado y la pantalla de desarrollo. */
    fun rowCounts(): Map<String, Long>
}
