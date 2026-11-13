package io.github.marioalr.tpvca.data.security

import io.github.marioalr.tpvca.sync.link.LabFileRules
import java.io.File

/**
 * Los resultados de laboratorio ya bajados: cifrados con AES-GCM ([SecretWrapper] con su propia llave del Keystore), en `noBackupFilesDir` y por SHA-256. LRU con
 * tope ([maxBytes]: 200 MB en el celular, 500 en la tablet, `PENDIENTES_ANDROID.md`). Se borra al revocar el equipo, llave incluida. Lo leído se revisa contra su SHA-256: un archivo
 * dañado o una llave perdida solo vuelve a bajar.
 *
 * **Lo que la tablet agregó y falta subir** (K7) vive aparte, en `pendientes/`, igual de cifrado: no entra al LRU (nunca se tira lo que no está en la nube) y estar ahí es lo que
 * dice que falta subirlo, como `LabFileState.UploadedTo` de la PC. Al subirse pasa a la caché ([settle]).
 */
class LabFileCache(private val dir: File, private val wrapper: SecretWrapper, private val maxBytes: Long = PHONE_MAX_BYTES) {
    private val pendingDir = File(dir, PENDING)

    @Synchronized
    fun read(sha256: String): ByteArray? = readFrom(File(pendingDir, name(sha256)), sha256, touch = false) ?: readFrom(File(dir, name(sha256)), sha256, touch = true)

    private fun readFrom(file: File, sha256: String, touch: Boolean): ByteArray? {
        if (!file.isFile) return null
        return try {
            val plain = wrapper.unwrap(file.readBytes())
            if (LabFileRules.sha256(plain) != name(sha256)) {
                file.delete()
                null
            } else {
                if (touch) file.setLastModified(System.currentTimeMillis())
                plain
            }
        } catch (_: SecretUnreadableException) {
            file.delete()
            null
        }
    }

    @Synchronized
    fun write(sha256: String, content: ByteArray) {
        writeTo(dir, sha256, content)
        trim(keep = name(sha256))
    }

    private fun writeTo(folder: File, sha256: String, content: ByteArray) {
        folder.mkdirs()
        val temporary = File(folder, name(sha256) + ".tmp")
        temporary.writeBytes(wrapper.wrap(content))
        check(temporary.renameTo(File(folder, name(sha256)))) { "No se pudo guardar el resultado." }
    }

    /** Un resultado agregado en este equipo: se guarda antes que su fila y queda pendiente de subir. Ya subido, o ya en la caché, no se vuelve a pendiente. */
    @Synchronized
    fun writePending(sha256: String, content: ByteArray) {
        if (File(dir, name(sha256)).isFile || File(pendingDir, name(sha256)).isFile) return
        writeTo(pendingDir, sha256, content)
    }

    /** Los SHA-256 que faltan por subir, los más nuevos primero. */
    @Synchronized
    fun pending(): List<String> = pendingDir.listFiles().orEmpty().filter { it.isFile && !it.name.endsWith(".tmp") }.sortedByDescending { it.lastModified() }.map { it.name }

    /** Está aquí, pendiente o en la caché: solo mira si el archivo existe. No lo descifra, no lo marca como usado ni borra nada (para el estado de la lista; abrirlo sí lo revisa). */
    @Synchronized
    fun contains(sha256: String): Boolean = File(pendingDir, name(sha256)).isFile || File(dir, name(sha256)).isFile

    @Synchronized
    fun isPending(sha256: String): Boolean = File(pendingDir, name(sha256)).isFile

    /** Ya está en la nube (o ya no hace falta): pasa a la caché como cualquier otro, donde el LRU manda. */
    @Synchronized
    fun settle(sha256: String) {
        val pending = File(pendingDir, name(sha256))
        if (!pending.isFile) return
        dir.mkdirs()
        val target = File(dir, name(sha256))
        if (target.isFile || pending.renameTo(target)) {
            pending.delete()
            trim(keep = name(sha256))
        }
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
        const val TABLET_MAX_BYTES = 500_000_000L
        private const val PENDING = "pendientes"
    }
}
