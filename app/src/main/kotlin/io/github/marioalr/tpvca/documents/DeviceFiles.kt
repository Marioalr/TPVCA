package io.github.marioalr.tpvca.documents

import android.content.Context
import io.github.marioalr.tpvca.data.security.LabFileCache

/**
 * Lo que se borra al revocar o limpiar el equipo, fuera de la base: borradores, lo compartido y lo que se estaba viendo (`cache/compartidos`, `cache/vista`), la caché de laboratorio con su llave y lo de
 * las bibliotecas. La credencial y la llave de la base las borra `LinkFlow`, porque no siempre se van (dos 401 conservan el `DeviceId`).
 */
class DeviceFiles(context: Context, private val labCache: LabFileCache) {
    private val app = context.applicationContext

    fun erase() {
        app.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        app.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        labCache.destroy()
        // La base de la app ya se borró; lo que queda aquí es de las bibliotecas (los eventos locales de datatransport)
        // La de WorkManager se queda: la biblioteca la tiene abierta y no lleva datos. Sus trabajos ya se cancelaron
        app.databaseList().filterNot { it.startsWith(WORK_DATABASE_PREFIX) }.forEach(app::deleteDatabase)
    }

    private companion object {
        const val WORK_DATABASE_PREFIX = "androidx.work"
    }
}
