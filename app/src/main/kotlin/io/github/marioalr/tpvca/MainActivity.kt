package io.github.marioalr.tpvca

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.marioalr.tpvca.domain.devices.DeviceRole
import io.github.marioalr.tpvca.link.LinkController
import io.github.marioalr.tpvca.link.gateFor
import io.github.marioalr.tpvca.sync.SyncContract
import io.github.marioalr.tpvca.ui.home.HomeLayout
import io.github.marioalr.tpvca.ui.link.AppRoot
import io.github.marioalr.tpvca.ui.link.CameraQrReader
import io.github.marioalr.tpvca.ui.link.LinkActions

/** `FragmentActivity` porque `BiometricPrompt` lo pide. */
class MainActivity : FragmentActivity() {
    private lateinit var prompt: BiometricPrompt

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val controller = (application as TpvcaApplication).graph.controller
        // Se crea aquí: BiometricPrompt no se puede crear ya empezada la actividad
        prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = controller.unlocked()
            },
        )
        val actions = Actions(controller)
        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            val state by controller.state.collectAsStateWithLifecycle()
            val locked by controller.locked.collectAsStateWithLifecycle()
            val secure by controller.deviceSecure.collectAsStateWithLifecycle()
            AppRoot(
                state = state,
                gate = gateFor(state, controller.role, locked, secure),
                deviceSecure = secure,
                versionName = BuildConfig.VERSION_NAME,
                contractRevision = SyncContract.REVISION,
                layout = HomeLayout.from(windowSizeClass.widthSizeClass),
                actions = actions,
                qrReader = { onText, onNoPermission -> CameraQrReader(onText, onNoPermission) },
            )
        }
    }

    /** Huella o la credencial del equipo: `BIOMETRIC_WEAK or DEVICE_CREDENTIAL` es la combinación que sirve en todas las versiones desde la 26. */
    private fun showUnlockPrompt() {
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.locked_prompt_title))
                .setSubtitle(getString(R.string.locked_prompt_subtitle))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build(),
        )
    }

    private inner class Actions(private val controller: LinkController) : LinkActions {
        override fun link(url: String, token: String, clinicName: String, role: DeviceRole) = controller.link(url, token, clinicName, role)

        override fun replaceToken(token: String, url: String?, confirmedHost: String?) = controller.replaceToken(token, url, confirmedHost)

        override fun requestToken() = controller.requestToken()

        override fun resumeBrake() = controller.resumeBrake()

        override fun refreshNow() = controller.refreshNow()

        override fun forget() = controller.forget()

        override fun acknowledgeRevocation() = controller.acknowledgeRevocation()

        override fun unlock() = showUnlockPrompt()

        override fun openSecuritySettings() = startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
    }
}
