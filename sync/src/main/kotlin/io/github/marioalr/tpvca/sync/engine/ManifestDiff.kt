package io.github.marioalr.tpvca.sync.engine

import io.github.marioalr.tpvca.sync.SyncSchema
import io.github.marioalr.tpvca.sync.SyncTable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * La revisión del manifiesto del primer arranque: `_tpvc_meta.manifest` contra el `schema.json` incluido. Cualquier diferencia deja la
 * app en solo lectura ("Actualice la app"); los datos siguen bajando, y lo que la app no conoce se ignora al bajar.
 *
 * [unknownInCloud] es lo que agregó una PC más nueva; [missingInCloud], lo que esta app espera y la nube no tiene (§7.3).
 */
data class ManifestDiff(val unknownInCloud: List<String>, val missingInCloud: List<String>, val unreadable: Boolean = false) {
    val isEmpty: Boolean get() = unknownInCloud.isEmpty() && missingInCloud.isEmpty() && !unreadable

    fun describe(): String = buildString {
        if (unreadable) append("La nube no trae un manifiesto que se pueda leer. ")
        if (unknownInCloud.isNotEmpty()) append("La nube tiene: ").append(unknownInCloud.joinToString()).append(". ")
        if (missingInCloud.isNotEmpty()) append("A la nube le falta: ").append(missingInCloud.joinToString()).append(". ")
    }.trim()

    companion object {
        const val MESSAGE: String = "Actualice la app: la nube y esta versión no tienen el mismo formato. Puede consultar, pero no guardar cambios."

        val UNREADABLE = ManifestDiff(emptyList(), emptyList(), unreadable = true)

        fun compare(bundled: SyncSchema, cloudManifest: String?): ManifestDiff {
            val cloud = cloudManifest?.takeIf { it.isNotBlank() }
                ?.let { runCatching { SyncSchema.parse(it) }.getOrNull() }
                ?: return UNREADABLE
            val app = bundled.parts()
            val remote = cloud.parts()
            return ManifestDiff((remote - app).sorted(), (app - remote).sorted())
        }

        /** Todo lo que el manifiesto promete: tablas, columnas con su tipo y valores de enum. */
        private fun SyncSchema.parts(): Set<String> = buildSet {
            for (table in tables) addAll(table.parts())
            add(tombstones.table)
            for (column in tombstones.columns) add("${tombstones.table}.${column.jsonObject["name"]?.jsonPrimitive?.content}")
            for (other in otherTables) add(other.table)
        }

        private fun SyncTable.parts(): List<String> = buildList {
            add(table)
            for (column in columns) {
                add("$table.${column.name}:${column.remoteType}")
                for (name in column.enumValues.keys) add("$table.${column.name}=$name")
            }
        }
    }
}
