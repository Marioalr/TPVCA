package io.github.marioalr.tpvca.documents

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.github.marioalr.tpvca.data.security.DataGate
import java.io.File
import java.util.UUID

/**
 * Los documentos en claro que la app necesita un momento. Toda escritura pasa por [DataGate]: una que empezó antes de un borrado falla, y el borrado espera a la que está en curso.
 *
 * - **Para ver** (`cache/vista`): fuera del `FileProvider`. Se borra al cerrar la vista ([deleteView]) y al pasar la app a segundo plano ([purgeViews]).
 * - **Para compartir** (`cache/compartidos`): lo único que ve el `FileProvider`, en su propia carpeta y con un nombre sin datos del paciente. La intención solo lleva el archivo, con permiso
 *   de lectura para ese archivo: ningún dato en sus extras. **No se puede borrar al irse a la otra app**, que lo lee después; se borra a los 15 min, al volver a la app o al abrirla
 *   ([purgeOld]). Con la app cerrada no corre nada, así que el último compartido puede quedar en la caché hasta entonces. Al revocar se borra todo ([DeviceFiles]).
 */
class SharedDocuments(context: Context, val gate: DataGate = DataGate(), private val now: () -> Long = System::currentTimeMillis) {
    private val app = context.applicationContext
    val folder: File = File(app.cacheDir, FOLDER)
    val viewFolder: File = File(app.cacheDir, VIEW_FOLDER)

    /** Antes de empezar la operación: leer, bajar, dibujar. */
    fun ticket(): Long = gate.ticket()

    fun write(ticket: Long, fileName: String, write: (File) -> Unit): File {
        purgeOld()
        return writeIn(folder, ticket, fileName, write)
    }

    fun writeView(ticket: Long, fileName: String, write: (File) -> Unit): File = writeIn(viewFolder, ticket, fileName, write)

    /** Compartir lo que se está viendo: una copia aparte, porque la de la vista se borra al cerrarla. */
    fun shareCopy(ticket: Long, viewed: File): File = write(ticket, viewed.name) { viewed.copyTo(it, overwrite = true) }

    private fun writeIn(parent: File, ticket: Long, fileName: String, write: (File) -> Unit): File = gate.write(ticket) {
        val dir = File(parent, UUID.randomUUID().toString())
        check(dir.mkdirs() || dir.isDirectory) { "No se pudo preparar el documento." }
        try {
            File(dir, fileName).also(write)
        } catch (e: Exception) {
            // Sin la mitad de un documento en la caché, ni si no cupo en el disco
            dir.deleteRecursively()
            throw e
        }
    }

    fun deleteView(file: File) {
        val dir = file.parentFile ?: return
        if (dir.parentFile == viewFolder) dir.deleteRecursively()
    }

    fun purgeViews() {
        viewFolder.deleteRecursively()
    }

    fun shareIntent(file: File, mimeType: String): Intent {
        val uri = FileProvider.getUriForFile(app, app.packageName + AUTHORITY_SUFFIX, file)
        return Intent(Intent.ACTION_SEND)
            .setType(mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newRawUri(null, uri) }
    }

    fun purgeOld(maxAgeMillis: Long = MAX_AGE_MILLIS) {
        folder.listFiles()?.forEach { if (now() - it.lastModified() > maxAgeMillis) it.deleteRecursively() }
    }

    companion object {
        const val FOLDER = "compartidos"
        const val VIEW_FOLDER = "vista"
        const val AUTHORITY_SUFFIX = ".documentos"
        const val MAX_AGE_MILLIS = 15 * 60 * 1000L
    }
}
