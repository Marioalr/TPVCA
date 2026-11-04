package io.github.marioalr.tpvca

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.marioalr.tpvca.link.AppGraph

/** La app: sus piezas viven aquí y la cadencia sigue al proceso, no a la pantalla (`ProcessLifecycleOwner`, plan §7.2). */
class TpvcaApplication : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = graph.onForeground()

                override fun onStop(owner: LifecycleOwner) = graph.onBackground()
            },
        )
    }
}
