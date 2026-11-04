package io.github.marioalr.tpvca.ui.link

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.marioalr.tpvca.domain.devices.DeviceRole
import io.github.marioalr.tpvca.link.Gate
import io.github.marioalr.tpvca.sync.link.LinkCode
import io.github.marioalr.tpvca.sync.link.LinkProblem
import io.github.marioalr.tpvca.sync.link.LinkState
import io.github.marioalr.tpvca.ui.home.HomeLayout
import io.github.marioalr.tpvca.ui.home.HomeLink
import io.github.marioalr.tpvca.ui.home.HomeScreen
import io.github.marioalr.tpvca.ui.phone.VincularScreen
import io.github.marioalr.tpvca.ui.theme.TpvcTheme
import io.github.marioalr.tpvca.ui.theme.TpvcThemes

/** Qué pantalla toca. Las de vincular van siempre en tema claro; el inicio de K0 sigue como estaba. */
@Composable
fun AppRoot(
    state: LinkState?,
    gate: Gate,
    deviceSecure: Boolean,
    versionName: String,
    contractRevision: Int,
    layout: HomeLayout,
    actions: LinkActions,
    qrReader: QrReader,
    /** Las pantallas del celular (K4): solo con el rol aprobado de celular. */
    phone: (@Composable (LinkState.Linked) -> Unit)? = null,
    /** La tablet, copia de la PC (K6): solo con el rol aprobado de tablet. */
    tablet: (@Composable (LinkState.Linked) -> Unit)? = null,
    /** La primera pantalla del celular sin vincular es la del diseño (`Vincular`), no el inicio de K0. */
    designedLinkScreen: Boolean = false,
) {
    when (gate) {
        Gate.Loading -> Light { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Box(Modifier.fillMaxSize()) } }
        Gate.NoSecureLock -> Light { NoSecureLockScreen(actions) }
        Gate.Locked -> Light {
            LaunchedEffect(Unit) { actions.unlock() }
            LockedScreen(actions::unlock)
        }
        Gate.Open -> when (state) {
            null -> Unit
            is LinkState.NotLinked -> NotLinked(state, deviceSecure, versionName, contractRevision, layout, actions, qrReader, designedLinkScreen)
            is LinkState.Linked -> if (phone != null && state.role == DeviceRole.AndroidMobileAdmin) {
                Light { phone(state) }
            } else if (tablet != null && state.role == DeviceRole.AndroidTablet) {
                TpvcTheme { tablet(state) }
            } else TpvcTheme {
                HomeScreen(versionName, contractRevision, layout, link = HomeLink.Linked(state.role, state.code, state.clinicName, state.notice, state.braked), onResumeBrake = actions::resumeBrake)
            }
            is LinkState.WaitingApproval -> Light { WaitingScreen(state, actions) }
            is LinkState.Downloading -> Light { DownloadingScreen(state, actions) }
            is LinkState.NeedsToken -> Light { NeedsToken(state, deviceSecure, actions, qrReader) }
            is LinkState.WrongRole -> Light { WrongRoleScreen(actions) }
            is LinkState.CloudProblem -> Light { CloudProblemScreen(state, actions) }
            is LinkState.Revoked -> Light { RevokedScreen(state, actions) }
            is LinkState.CredentialLost -> Light { CredentialLostScreen(state, actions) }
            LinkState.LocalError -> Light { LocalErrorScreen(actions) }
        }
    }
}

@Composable
private fun Light(content: @Composable () -> Unit) = TpvcTheme(TpvcThemes.claro, content)

private enum class LinkStep { Home, Form, Scan }

@Composable
private fun NotLinked(
    state: LinkState.NotLinked,
    deviceSecure: Boolean,
    versionName: String,
    contractRevision: Int,
    layout: HomeLayout,
    actions: LinkActions,
    qrReader: QrReader,
    designedLinkScreen: Boolean,
) {
    // Un problema al vincular regresa al formulario
    var step by remember(state.problem) { mutableStateOf(if (state.problem != null) LinkStep.Form else LinkStep.Home) }
    val defaultRole = if (layout == HomeLayout.Narrow) DeviceRole.AndroidMobileAdmin else DeviceRole.AndroidTablet
    var role by remember { mutableStateOf(defaultRole) }
    when (step) {
        LinkStep.Home -> if (designedLinkScreen && layout == HomeLayout.Narrow) {
            Light {
                VincularScreen(
                    deviceSecure,
                    actions,
                    onScan = {
                        role = DeviceRole.AndroidMobileAdmin
                        step = LinkStep.Scan
                    },
                    onPaste = { step = LinkStep.Form },
                )
            }
        } else TpvcTheme {
            HomeScreen(versionName, contractRevision, layout, onLink = { step = LinkStep.Form })
        }
        LinkStep.Form -> Light {
            LinkFormScreen(
                state.problem,
                deviceSecure,
                role,
                actions,
                onScan = {
                    role = it
                    step = LinkStep.Scan
                },
                onBack = { step = LinkStep.Home },
            )
        }
        LinkStep.Scan -> Light {
            QrScanScreen(
                qrReader,
                onData = {
                    // Sale del escáner ya: si lo rechaza, el formulario dice por qué
                    step = LinkStep.Form
                    actions.link(it.url, it.token, it.clinicName, role)
                },
                decode = LinkCode::decode,
                onBack = { step = LinkStep.Form },
            )
        }
    }
}

/** Lo pegado o escaneado se queda aquí, en memoria, por si hay que confirmar el host: no viaja en el estado. */
@Composable
private fun NeedsToken(state: LinkState.NeedsToken, deviceSecure: Boolean, actions: LinkActions, qrReader: QrReader) {
    var scanning by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf<Pair<String, String?>?>(null) }
    fun submit(token: String, url: String?) {
        submitted = token to url
        actions.replaceToken(token, url, null)
    }
    if (scanning) {
        QrScanScreen(
            qrReader,
            onData = {
                // Sale del escáner ya: si lo rechaza, la pantalla del token dice por qué
                scanning = false
                submit(it.token, it.url)
            },
            decode = LinkCode::decode,
            onBack = { scanning = false },
        )
    } else {
        TokenScreen(state, deviceSecure, actions, onSubmit = ::submit, onScan = { scanning = true })
        val change = state.problem as? LinkProblem.HostChange
        val pending = submitted
        if (change != null && pending != null) {
            HostChangeDialog(
                change.host,
                onConfirm = {
                    submitted = null
                    actions.replaceToken(pending.first, pending.second, change.host)
                },
                onCancel = { submitted = null },
            )
        }
    }
}
