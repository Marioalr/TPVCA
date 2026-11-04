package io.github.marioalr.tpvca.ui.phone

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.marioalr.tpvca.documents.DocumentPages
import io.github.marioalr.tpvca.ui.link.PatientDataWindow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_RENDER_WIDTH = 1400

/**
 * Ver un PDF o una foto dentro de la app, sin depender de un visor del equipo ni sacar el archivo. Se ven **todas** las páginas: cada una se dibuja al llegar a ella y se suelta al salir de la
 * lista. Lo que no se puede dibujar lo dice en su lugar y se puede compartir el archivo completo. Al cerrar o al irse la app, el archivo se borra.
 */
@Composable
fun PreviewScreen(env: PhoneEnv, doc: PreviewDoc, onClose: () -> Unit) {
    PatientDataWindow()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }
    val nav = remember { PhoneNav({}, onClose, {}, {}, {}) }
    val close by rememberUpdatedState(onClose)
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(doc) { onDispose { env.documents.deleteView(doc.file) } }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) close() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val pages by produceState<Loaded<DocumentPages>>(Loaded.Loading, doc) {
        val opened = try {
            withContext(Dispatchers.IO) { DocumentPages.open(doc.file, doc.mimeType) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        value = opened?.let { Loaded.Ready(it) } ?: Loaded.Failed
        if (opened != null) {
            try {
                awaitCancellation()
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { opened.close() }
            }
        }
    }

    Column(Modifier.fillMaxSize().testTag(PhoneTags.PREVIEW)) {
        DetailHeader(doc.title, nav) {
            PButton("Compartir", height = 40.dp, modifier = Modifier.padding(end = 8.dp)) {
                val ticket = env.documents.ticket()
                scope.launch {
                    failed = false
                    val intent = withContext(Dispatchers.IO) {
                        runCatching { env.documents.shareIntent(env.documents.shareCopy(ticket, doc.file), doc.mimeType) }.getOrNull()
                    }
                    if (intent == null) failed = true else context.startActivity(Intent.createChooser(intent, PhoneText.SHARE_TITLE))
                }
            }
        }
        if (failed) PText(PhoneText.SHARE_FAILED, Modifier.padding(horizontal = 16.dp), size = 12.sp, color = PhoneColors.Danger)
        when (val state = pages) {
            Loaded.Loading -> Message(PhoneText.LOADING)
            Loaded.Failed -> Message(PhoneText.PREVIEW_FAILED)
            is Loaded.Ready -> DocumentList(state.value, doc.title)
        }
    }
}

@Composable
private fun DocumentList(pages: DocumentPages, title: String) {
    if (pages.count == 0) {
        Message(PhoneText.PREVIEW_FAILED)
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }.toInt().coerceAtMost(MAX_RENDER_WIDTH)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items((0 until pages.count).toList(), key = { it }) { index ->
                val label = "$title, página ${index + 1} de ${pages.count}"
                // Sale de la lista y deja de estar en memoria: el mapa de bits solo lo tiene este renglón
                val page by load(index, widthPx) { pages.render(index, widthPx) }
                val box = Modifier.fillMaxWidth().aspectRatio(1f / pages.aspect).background(Color.White).semantics { contentDescription = label }
                when (val state = page) {
                    is Loaded.Ready -> Image(state.value.asImageBitmap(), null, box, contentScale = ContentScale.Fit, alignment = Alignment.TopCenter)
                    Loaded.Loading -> androidx.compose.foundation.layout.Box(box)
                    Loaded.Failed -> androidx.compose.foundation.layout.Box(box, contentAlignment = Alignment.Center) { Message("No se pudo mostrar la página ${index + 1}. Compartir manda el archivo completo.") }
                }
            }
        }
    }
}
