package io.github.marioalr.tpvca.data.security

import io.github.marioalr.tpvca.sync.link.LabFileRules
import java.io.File

/**
 * Los resultados de laboratorio ya bajados: cifrados con AES-GCM ([SecretWrapper] con su propia llave del Keystore), en `noBackupFilesDir` y por SHA-256. LRU con
 * tope ([maxBytes]: 200 MB en el celular, `PENDIENTES_ANDROID.md`). Se borra al revocar el equipo, llave incluida. Lo leído se revisa contra su SHA-256: un archivo
 * dañado o una llave perdida solo vuelve a bajar.
 */
class LabFileCache(private val dir: File, private val wrapper: SecretWrapper, private val maxBytes: Long = PHONE_MAX_BYTES) {
    @Synchronized
    fun read(sha256: String): ByteArray? {
        val file = File(dir, name(sha256))
        if (!file.isFile) return null
        return try {
            val plain = wrapper.unwrap(file.readBytes())
            if (LabFileRules.sha256(plain) != name(sha256)) {
                file.delete()
                null
            } else {
                file.setLastModified(System.currentTimeMillis())
                plain
            }
        } catch (_: SecretUnreadableException) {
            file.delete()
            null
        }
    }

    @Synchronized
    fun write(sha256: String, content: ByteArray) {
        dir.mkdirs()
        val temporary = File(dir, name(sha256) + ".tmp")
        temporary.writeBytes(wrapper.wrap(content))
        check(temporary.renameTo(File(dir, name(sha256)))) { "No se pudo guardar el resultado." }
        trim(keep = name(sha256))
    }

    private fun trim(keep: String) {
        val files = dir.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= maxBytes) break
            if (file.name == keep) continue
            total -= file.length()
            file.delete()
        }
    }

    /** Se borra todo y la llave: lo que hubiera quedado ya no se podría abrir. */
    @Synchronized
    fun destroy() {
        dir.deleteRecursively()
        wrapper.destroy()
    }

    /** Solo hexadecimal: viene de la base, y nunca se arma una ruta con otra cosa. */
    private fun name(sha256: String): String = sha256.lowercase(java.util.Locale.ROOT).also { require(it.all { c -> c in '0'..'9' || c in 'a'..'f' }) { "SHA-256 inválido" } }

    companion object {
        const val PHONE_MAX_BYTES = 200_000_000L
    }
}
