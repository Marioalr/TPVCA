package io.github.marioalr.tpvca.dev

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.marioalr.tpvca.BuildConfig
import io.github.marioalr.tpvca.sync.engine.SyncOutcome
import io.github.marioalr.tpvca.ui.theme.TpvcTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pantalla oculta de desarrollo: una sincronización contra una base de prueba, con las tablas, cuántas filas tienen y el último error.
 * Solo existe en `src/debug`: la compilación de publicación no la trae. La URL y el token salen de `local.properties`, o se pegan aquí.
 */
class DevSyncActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val runner = DevSyncRunner(this)
        setContent {
            TpvcTheme {
                Surface(Modifier.fillMaxSize()) {
                    DevSyncScreen(runner)
                }
            }
        }
    }
}

@Composable
private fun DevSyncScreen(runner: DevSyncRunner) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(BuildConfig.TPVC_DEV_URL) }
    var token by remember { mutableStateOf(BuildConfig.TPVC_DEV_TOKEN) }
    var running by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<SyncOutcome?>(null) }
    var status by remember { mutableStateOf<DevStatus?>(null) }

    fun work(action: suspend CoroutineScope.() -> Unit) {
        scope.launch {
            running = true
            try {
                status = withContext(Dispatchers.IO) {
                    action()
                    runner.status()
                }
            } finally {
                running = false
            }
        }
    }

    LaunchedEffect(Unit) { work { } }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Sincronización de desarrollo", style = MaterialTheme.typography.titleLarge)
        Text("Baja datos y escribe su latido: la PC debe aprobar este equipo. Use solo una base de prueba.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("URL de Turso") }, singleLine = true)
        OutlinedTextField(
            token,
            { token = it },
            Modifier.fillMaxWidth(),
            label = { Text("Token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { work { outcome = runner.sync(url, token) } }, enabled = !running) { Text("Sincronizar") }
            OutlinedButton(onClick = { work { runner.wipe(); outcome = null } }, enabled = !running) { Text("Borrar la base") }
            OutlinedButton(onClick = { work { runner.resumeBrake() } }, enabled = !running && status?.brakeTripped == true) { Text("Reanudar") }
        }
        if (running) CircularProgressIndicator()
        outcome?.let { result ->
            Text("Resultado: ${result::class.simpleName}", style = MaterialTheme.typography.titleSmall)
            if (result is SyncOutcome.Completed) {
                Text("${if (result.fullDownload) "Descarga completa" else "Incremental"}: ${result.applied} aplicadas, ${result.rowsRead} lecturas estimadas")
            }
            result.message?.let { Text(it) }
        }
        status?.let { DevStatusBlock(it) }
    }
}

@Composable
private fun DevStatusBlock(status: DevStatus) {
    HorizontalDivider()
    Text("Equipo: ${status.deviceId} (${status.deviceRole ?: "sin rol"})", style = MaterialTheme.typography.bodySmall)
    Text("Consultorio: ${status.clinicId ?: "—"}", style = MaterialTheme.typography.bodySmall)
    Text("Última buena: ${status.lastSuccess ?: "—"}", style = MaterialTheme.typography.bodySmall)
    Text("Último error: ${status.lastError ?: "—"}", style = MaterialTheme.typography.bodySmall)
    Text("Solo lectura: ${status.readOnly ?: "no"}", style = MaterialTheme.typography.bodySmall)
    Text(
        "Hoy: ${status.fullPullsToday} descargas completas, ${status.rowsReadToday} lecturas; freno ${if (status.brakeTripped) "ACTIVO" else "apagado"}; ${status.parked} apartadas",
        style = MaterialTheme.typography.bodySmall,
    )
    HorizontalDivider()
    for ((table, count) in status.counts) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(table)
            Text(count.toString())
        }
    }
}
