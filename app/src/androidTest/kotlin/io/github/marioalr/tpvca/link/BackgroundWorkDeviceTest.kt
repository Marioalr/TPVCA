package io.github.marioalr.tpvca.link

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import io.github.marioalr.tpvca.TpvcaApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Los trabajadores en un equipo de verdad, con la app sin vincular: sin credencial no tocan la red ni crean una base, y lo compartido viejo se borra. */
@RunWith(AndroidJUnit4::class)
class BackgroundWorkDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun laSincronizacionDeFondoSinVincularNoHaceNada() = runBlocking {
        val result = TestListenableWorkerBuilder<SyncWorker>(context).build().doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertFalse(context.getDatabasePath("tpvca.db").exists())
    }

    @Test
    fun loCompartidoViejoSeBorraConLaAppCerrada() = runBlocking {
        val documents = (context as TpvcaApplication).graph.phone.documents
        val folder = File(documents.folder, "viejo").also { it.mkdirs() }
        val old = File(folder, "receta.pdf").also { it.writeText("x") }
        old.setLastModified(System.currentTimeMillis() - 60 * 60 * 1000L)
        folder.setLastModified(System.currentTimeMillis() - 60 * 60 * 1000L)
        val result = TestListenableWorkerBuilder<SharedPurgeWorker>(context).build().doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertFalse(old.exists())
    }
}
