package io.github.marioalr.tpvca.data.security

import java.io.File
import java.io.FileOutputStream

/**
 * Un archivo envuelto con [wrapper]. Va en `noBackupFilesDir`, que Android nunca copia a un respaldo ni a otro equipo. Escribe en un
 * temporal y lo renombra: un corte a medio guardar deja el archivo anterior.
 */
class WrappedFile(private val file: File, private val wrapper: SecretWrapper) {
    val exists: Boolean get() = file.exists()

    /** `null` si no existe. @throws SecretUnreadableException si existe y no se puede abrir. */
    fun read(): ByteArray? = if (file.exists()) wrapper.unwrap(file.readBytes()) else null

    fun write(plain: ByteArray) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(temp).use { out ->
            out.write(wrapper.wrap(plain))
            out.fd.sync()
        }
        if (!temp.renameTo(file)) {
            temp.delete()
            throw java.io.IOException("No se pudo guardar un dato de este equipo.")
        }
    }

    /** Borra el archivo y la llave que lo abre. */
    fun destroy() {
        file.delete()
        wrapper.destroy()
    }
}
