package io.github.marioalr.tpvca.ui.link

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.marioalr.tpvca.R
import io.github.marioalr.tpvca.domain.devices.DeviceRole
import io.github.marioalr.tpvca.sync.engine.SyncOutcome
import io.github.marioalr.tpvca.sync.link.LinkData
import io.github.marioalr.tpvca.sync.link.LinkProblem
import io.github.marioalr.tpvca.sync.link.LinkState
import io.github.marioalr.tpvca.ui.theme.LocalTpvcColors

object LinkTags {
    const val FORM = "link-form"
    const val ROLE_PHONE = "link-role-phone"
    const val ROLE_TABLET = "link-role-tablet"
    const val URL = "link-url"
    const val TOKEN = "link-token"
    const val SUBMIT = "link-submit"
    const val SCAN = "link-scan"
    const val PROBLEM = "link-problem"
    const val SUFFIX = "waiting-suffix"
    const val WAITING_REFRESH = "waiting-refresh"
    const val WAITING_CANCEL = "waiting-cancel"
    const val TOKEN_SCREEN = "token-screen"
    const val TOKEN_SUBMIT = "token-submit"
    const val REVOKED_OK = "revoked-ok"
    const val UNLOCK = "locked-unlock"
    const val SCAN_SCREEN = "scan-screen"
    const val SETTINGS = "open-settings"
    const val FORGET = "forget"
    const val FORGET_CONFIRM = "forget-confirm"
    const val HOST_CONFIRM = "host-confirm"
    const val RESUME = "brake-resume"
    const val CLOUD_REPLACE = "cloud-replace"
    const val TOKEN_URL = "token-url"
}

/** Lo que las pantallas le piden a la app. */
interface LinkActions {
    fun link(url: String, token: String, clinicName: String, role: DeviceRole)

    fun replaceToken(token: String, url: String?, confirmedHost: String?)

    fun requestToken()

    fun resumeBrake()

    fun refreshNow()

    fun forget()

    fun acknowledgeRevocation()

    fun unlock()

    fun openSecuritySettings()
}

/** Quién lee el QR: la cámara en la app, un texto fijo en las pruebas. Recibe cada texto que ve. */
typealias QrReader = @Composable (onText: (String) -> Unit, onNoPermission: () -> Unit) -> Unit

/**
 * Sin capturas de pantalla ni miniatura en Recientes mientras la pantalla pueda mostrar el token: al pegarlo y con el QR en la cámara. El
 * plan no pide `FLAG_SECURE` en ninguna otra pantalla.
 */
@Composable
fun SecureWindow() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val window = context.findActivity()?.window
        secureHolders += 1
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            secureHolders -= 1
            if (secureHolders == 0) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

/** Cuántas pantallas lo piden ahora: al pasar de una a otra, primero entra la nueva y después sale la anterior. Solo en el hilo principal. */
private var secureHolders = 0

/**
 * Datos de pacientes y la miniatura de Recientes (decisión de K4): desde la API 33 `setRecentsScreenshotEnabled(false)` en `MainActivity` la quita sin estorbar las
 * capturas. Antes de la 33 no existe esa opción, así que las pantallas con datos de pacientes llevan `FLAG_SECURE`.
 */
@Composable
fun PatientDataWindow() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) SecureWindow()
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun LinkPage(tag: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().then(if (tag != null) Modifier.testTag(tag) else Modifier),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun Warning(text: String, modifier: Modifier = Modifier) {
    val colors = LocalTpvcColors.current
    Surface(color = colors.warningSubtle, contentColor = colors.warningText, shape = MaterialTheme.shapes.medium, modifier = modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun problemText(problem: LinkProblem): String = when (problem) {
    LinkProblem.NoSecureLock -> stringResource(R.string.link_no_secure_lock)
    is LinkProblem.BadUrl -> problem.message
    LinkProblem.EmptyToken -> stringResource(R.string.link_empty_token)
    LinkProblem.BadToken -> stringResource(R.string.link_bad_token)
    is LinkProblem.HostChange -> stringResource(R.string.host_body, problem.host)
    LinkProblem.OtherClinic -> stringResource(R.string.link_other_clinic)
    is LinkProblem.CloudUnverified -> stringResource(R.string.link_cloud_unverified, problem.message)
    LinkProblem.CredentialLost -> stringResource(R.string.link_credential_lost)
}

@Composable
private fun roleName(role: DeviceRole): String =
    stringResource(if (role == DeviceRole.AndroidMobileAdmin) R.string.role_phone else R.string.role_tablet)

/** El rol, y luego el QR o la URL y el token. Lo escrito no sobrevive a girar la pantalla: el token no va en el estado que Android guarda. */
@Composable
fun LinkFormScreen(
    problem: LinkProblem?,
    deviceSecure: Boolean,
    defaultRole: DeviceRole,
    actions: LinkActions,
    onScan: (DeviceRole) -> Unit,
    onBack: () -> Unit,
) {
    SecureWindow()
    var role by remember { mutableStateOf(defaultRole) }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }

    LinkPage(LinkTags.FORM) {
        Title(stringResource(R.string.link_title))
        if (!deviceSecure) {
            Warning(stringResource(R.string.link_no_secure_lock))
            OutlinedButton(onClick = actions::openSecuritySettings, Modifier.heightIn(min = 48.dp).testTag(LinkTags.SETTINGS)) {
                Text(stringResource(R.string.link_open_settings))
            }
        } else if (problem != null) {
            Warning(problemText(problem), Modifier.testTag(LinkTags.PROBLEM))
        }
        Text(stringResource(R.string.link_role_question), style = MaterialTheme.typography.titleMedium)
        Column(Modifier.selectableGroup()) {
            RoleOption(DeviceRole.AndroidMobileAdmin, role, LinkTags.ROLE_PHONE) { role = it }
            RoleOption(DeviceRole.AndroidTablet, role, LinkTags.ROLE_TABLET) { role = it }
        }
        Button(
            onClick = { onScan(role) },
            enabled = deviceSecure,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(LinkTags.SCAN),
        ) { Text(stringResource(R.string.link_scan)) }
        Text(stringResource(R.string.link_or_paste), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            url,
            { url = it },
            Modifier.fillMaxWidth().testTag(LinkTags.URL),
            label = { Text(stringResource(R.string.link_url)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        )
        TokenField(token, { token = it })
        OutlinedButton(
            onClick = { actions.link(url, token, "", role) },
            enabled = deviceSecure && url.isNotBlank() && token.isNotBlank(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(LinkTags.SUBMIT),
        ) { Text(stringResource(R.string.link_submit)) }
        TextButton(onClick = onBack, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.link_back)) }
    }
}

@Composable
private fun RoleOption(role: DeviceRole, selected: DeviceRole, tag: String, onSelect: (DeviceRole) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = role == selected, role = Role.RadioButton, onClick = { onSelect(role) })
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = role == selected, onClick = null)
        Text(roleName(role), style = MaterialTheme.typography.bodyLarge)
    }
}

/** Oculto y sin corrección ni sugerencias del teclado: el token no debe quedar en el diccionario de nadie. */
@Composable
private fun TokenField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value,
        onChange,
        Modifier.fillMaxWidth().testTag(LinkTags.TOKEN),
        label = { Text(stringResource(R.string.link_token)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
    )
}

/** La cámara y lo que hace con cada texto: un código de TPVC sigue, cualquier otro lo dice y deja regresar a pegar a mano. */
@Composable
fun QrScanScreen(reader: QrReader, onData: (LinkData) -> Unit, decode: (String) -> LinkData?, onBack: () -> Unit) {
    SecureWindow()
    var notOurs by remember { mutableStateOf(false) }
    var noPermission by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }

    LinkPage(LinkTags.SCAN_SCREEN) {
        Title(stringResource(R.string.scan_title))
        Body(stringResource(R.string.scan_hint))
        if (noPermission) {
            Warning(stringResource(R.string.scan_no_permission))
        } else {
            reader(
                { text ->
                    if (!done) {
                        val data = decode(text)
                        if (data != null) {
                            done = true
                            onData(data)
                        } else {
                            notOurs = true
                        }
                    }
                },
                { noPermission = true },
            )
        }
        if (notOurs) Warning(stringResource(R.string.scan_not_ours), Modifier.testTag(LinkTags.PROBLEM))
        TextButton(onClick = onBack, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.link_back)) }
    }
}

@Composable
fun WaitingScreen(state: LinkState.WaitingApproval, actions: LinkActions) {
    LinkPage {
        Title(stringResource(R.string.waiting_title))
        Body(stringResource(R.string.waiting_suffix_hint))
        Text(
            state.suffix,
            style = MaterialTheme.typography.displayMedium,
            fontFamily = FontFamily.Monospace,
            color = LocalTpvcColors.current.accentForeground,
            modifier = Modifier.testTag(LinkTags.SUFFIX),
        )
        Text(stringResource(R.string.waiting_role, roleName(state.role)), style = MaterialTheme.typography.titleMedium)
        if (state.clinicName.isNotEmpty()) Text(stringResource(R.string.waiting_clinic, state.clinicName), style = MaterialTheme.typography.bodyMedium)
        Body(stringResource(R.string.waiting_steps))
        if (state.newIdentity) Warning(stringResource(R.string.waiting_new_identity))
        state.problem?.let { Warning(it, Modifier.testTag(LinkTags.PROBLEM)) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            OutlinedButton(onClick = actions::refreshNow, Modifier.heightIn(min = 48.dp).testTag(LinkTags.WAITING_REFRESH)) {
                Text(stringResource(R.string.waiting_refresh))
            }
        }
        if (state.braked) ResumeBrakeButton(actions)
        ForgetButton(stringResource(R.string.waiting_cancel), actions, LinkTags.WAITING_CANCEL)
    }
}

@Composable
fun DownloadingScreen(state: LinkState.Downloading, actions: LinkActions) {
    LinkPage {
        Title(stringResource(R.string.downloading_title))
        Body(stringResource(R.string.downloading_hint))
        state.notice?.let { Warning(it, Modifier.testTag(LinkTags.PROBLEM)) }
        CircularProgressIndicator()
        if (state.braked) {
            ResumeBrakeButton(actions)
        } else if (state.notice != null) {
            OutlinedButton(onClick = actions::refreshNow, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cloud_retry)) }
        }
    }
}

/** *Reanudar*: el freno no se quita solo, como en la PC. */
@Composable
fun ResumeBrakeButton(actions: LinkActions) {
    Button(onClick = actions::resumeBrake, Modifier.heightIn(min = 48.dp).testTag(LinkTags.RESUME)) { Text(stringResource(R.string.brake_resume)) }
}

/** Borrar nunca va a un solo toque: primero dice qué se pierde. */
@Composable
private fun ForgetButton(label: String, actions: LinkActions, tag: String = LinkTags.FORGET) {
    var asking by remember { mutableStateOf(false) }
    TextButton(onClick = { asking = true }, Modifier.heightIn(min = 48.dp).testTag(tag)) { Text(label) }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.forget_title)) },
            text = { Text(stringResource(R.string.forget_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        asking = false
                        actions.forget()
                    },
                    Modifier.testTag(LinkTags.FORGET_CONFIRM),
                ) { Text(stringResource(R.string.forget_confirm)) }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(stringResource(R.string.forget_cancel)) } },
        )
    }
}

/** Muestra el host nuevo antes de que el token salga hacia allá. */
@Composable
fun HostChangeDialog(host: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.host_title)) },
        text = { Text(stringResource(R.string.host_body, host)) },
        confirmButton = { TextButton(onClick = onConfirm, Modifier.testTag(LinkTags.HOST_CONFIRM)) { Text(stringResource(R.string.host_confirm)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.host_cancel)) } },
    )
}

@Composable
fun LocalErrorScreen(actions: LinkActions) {
    LinkPage {
        Title(stringResource(R.string.local_error_title))
        Body(stringResource(R.string.local_error_body))
        OutlinedButton(onClick = actions::refreshNow, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cloud_retry)) }
    }
}

/** La diferencia entre un 401 y un token mal copiado es solo el texto: los dos se resuelven pegando otro y ninguno borra nada por sí solo. */
@Composable
fun TokenScreen(state: LinkState.NeedsToken, deviceSecure: Boolean, actions: LinkActions, onSubmit: (String, String?) -> Unit, onScan: () -> Unit) {
    SecureWindow()
    var token by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    LinkPage(LinkTags.TOKEN_SCREEN) {
        Title(stringResource(R.string.token_title))
        Body(
            stringResource(
                when {
                    state.manual -> R.string.token_manual
                    state.erased -> R.string.token_erased
                    state.malformed -> R.string.token_malformed
                    else -> R.string.token_rejected
                },
            ),
        )
        if (state.pending > 0) {
            Warning(pluralStringResource(R.plurals.token_pending, state.pending, state.pending))
        } else if (!state.erased && !state.manual) {
            Body(stringResource(R.string.token_nothing_lost))
        }
        state.problem?.takeIf { it !is LinkProblem.HostChange }?.let { Warning(problemText(it), Modifier.testTag(LinkTags.PROBLEM)) }
        if (state.manual) {
            OutlinedTextField(
                url,
                { url = it },
                Modifier.fillMaxWidth().testTag(LinkTags.TOKEN_URL),
                label = { Text(stringResource(R.string.token_optional_url)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            )
        }
        TokenField(token, { token = it })
        Button(
            onClick = { onSubmit(token, url.ifBlank { null }) },
            enabled = deviceSecure && token.isNotBlank(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(LinkTags.TOKEN_SUBMIT),
        ) { Text(stringResource(R.string.token_submit)) }
        OutlinedButton(onClick = onScan, enabled = deviceSecure, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.token_scan))
        }
        if (state.pending == 0) ForgetButton(stringResource(R.string.token_other_cloud), actions)
        if (state.manual) TextButton(onClick = actions::refreshNow, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.token_back)) }
    }
}

@Composable
fun WrongRoleScreen(actions: LinkActions) {
    LinkPage {
        Title(stringResource(R.string.role_title))
        Body(stringResource(R.string.role_body))
        OutlinedButton(onClick = actions::refreshNow, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cloud_retry)) }
    }
}

@Composable
fun CloudProblemScreen(state: LinkState.CloudProblem, actions: LinkActions) {
    LinkPage {
        Title(stringResource(R.string.cloud_title))
        Warning(state.message, Modifier.testTag(LinkTags.PROBLEM))
        OutlinedButton(onClick = actions::refreshNow, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cloud_retry)) }
        if (state.canReplace) {
            OutlinedButton(onClick = actions::requestToken, Modifier.heightIn(min = 48.dp).testTag(LinkTags.CLOUD_REPLACE)) {
                Text(stringResource(R.string.token_title))
            }
        }
        if (state.canForget) ForgetButton(stringResource(R.string.token_other_cloud), actions)
    }
}

@Composable
fun RevokedScreen(state: LinkState.Revoked, actions: LinkActions) {
    LinkPage {
        Title(
            stringResource(
                when (state.reason) {
                    SyncOutcome.Revoked.REASON_REJECTED -> R.string.revoked_rejected_title
                    SyncOutcome.Revoked.REASON_LOST -> R.string.revoked_lost_title
                    else -> R.string.revoked_removed_title
                },
            ),
        )
        when {
            !state.erased -> Warning(pluralStringResource(R.plurals.revoked_pending_body, state.pending, state.pending))
            state.reason == SyncOutcome.Revoked.REASON_REJECTED -> Body(stringResource(R.string.revoked_rejected_body))
            else -> Body(stringResource(R.string.revoked_erased_body))
        }
        if (state.erased) {
            Button(onClick = actions::acknowledgeRevocation, Modifier.heightIn(min = 48.dp).testTag(LinkTags.REVOKED_OK)) {
                Text(stringResource(R.string.revoked_ok))
            }
        }
    }
}

@Composable
fun LockedScreen(onUnlock: () -> Unit) {
    LinkPage {
        Title(stringResource(R.string.locked_title))
        Body(stringResource(R.string.locked_body))
        Button(onClick = onUnlock, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(LinkTags.UNLOCK)) {
            Text(stringResource(R.string.locked_unlock))
        }
    }
}

@Composable
fun NoSecureLockScreen(actions: LinkActions) {
    LinkPage {
        Title(stringResource(R.string.no_lock_title))
        Body(stringResource(R.string.no_lock_body))
        Button(onClick = actions::openSecuritySettings, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(LinkTags.SETTINGS)) {
            Text(stringResource(R.string.link_open_settings))
        }
    }
}
