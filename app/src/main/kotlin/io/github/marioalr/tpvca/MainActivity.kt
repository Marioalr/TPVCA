package io.github.marioalr.tpvca

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import io.github.marioalr.tpvca.sync.SyncContract
import io.github.marioalr.tpvca.ui.home.HomeLayout
import io.github.marioalr.tpvca.ui.home.HomeScreen
import io.github.marioalr.tpvca.ui.theme.TpvcTheme

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            TpvcTheme {
                HomeScreen(
                    versionName = BuildConfig.VERSION_NAME,
                    contractRevision = SyncContract.REVISION,
                    layout = HomeLayout.from(windowSizeClass.widthSizeClass),
                )
            }
        }
    }
}
