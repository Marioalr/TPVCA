package io.github.marioalr.tpvca.documents

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/**
 * Lo que el médico comparte (receta, ticket, CSV, resultado): en `cache/compartidos`, lo único que ve el `FileProvider`. Cada archivo va en su propia carpeta y
 * con un nombre sin datos del paciente. La intención solo lleva el archivo, con permiso de lectura para ese archivo: ningún dato en sus extras.
 * Se borran por antigüedad al compartir y al volver a la app, y todos al revocar o borrar el equipo ([DeviceFiles]).
 */
class SharedDocuments(context: Context, private val now: () -> Long = System::currentTimeMillis) {
    private val app = context.applicationContext
    val folder: File = File(app.cacheDir, FOLDER)

    fun write(fileName: String, write: (File) -> Unit): File {
        purgeOld()
        val dir = File(folder, UUID.randomUUID().toString())
        check(dir.mkdirs() || dir.isDirectory) { "No se pudo preparar el documento." }
        return File(dir, fileName).also(write)
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
        const val AUTHORITY_SUFFIX = ".documentos"
        const val MAX_AGE_MILLIS = 60 * 60 * 1000L
    }
}
