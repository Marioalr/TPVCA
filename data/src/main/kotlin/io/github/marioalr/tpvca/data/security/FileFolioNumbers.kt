package io.github.marioalr.tpvca.data.security

import io.github.marioalr.tpvca.sync.link.FolioNumbers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException

/**
 * El último número de cada serie con letra, en un archivo **fuera de la base** (`OfflineFolioStore` de la PC): borrar la base por dos 401 y bajar todo otra vez no repite un folio ya impreso, aunque la PC
 * ya le haya cambiado el número a ese. Solo lleva series y números: ningún dato de un paciente. Va en `noBackupFilesDir`. Se reemplaza de una sola vez.
 */
class FileFolioNumbers(private val file: File) : FolioNumbers {
    @Synchronized
    override fun last(letteredSeries: String): Int = read()[letteredSeries] ?: 0

    @Synchronized
    override fun save(letteredSeries: String, number: Int) {
        val all = read().toMutableMap()
        if ((all[letteredSeries] ?: 0) >= number) return
        all[letteredSeries] = number
        file.parentFile?.mkdirs()
        val temporary = File(file.path + ".tmp")
        temporary.writeText(JsonObject(all.mapValues { JsonPrimitive(it.value) }).toString())
        if (!temporary.renameTo(file)) throw IOException("No se pudo guardar el último folio.")
    }

    @Synchronized
    override fun clear() {
        file.delete()
    }

    /** Dañado o ilegible: el máximo local sigue protegiendo lo que está en la base. */
    private fun read(): Map<String, Int> = try {
        if (!file.isFile) emptyMap() else Json.parseToJsonElement(file.readText()).jsonObject.mapValues { it.value.jsonPrimitive.int }
    } catch (_: IllegalArgumentException) {
        emptyMap()
    } catch (_: IOException) {
        emptyMap()
    }
}
