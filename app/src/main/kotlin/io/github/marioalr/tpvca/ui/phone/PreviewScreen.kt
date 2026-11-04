package io.github.marioalr.tpvca.ui.phone

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.marioalr.tpvca.ui.link.PatientDataWindow
import java.io.File

/**
 * Ver un PDF o una foto dentro de la app, sin depender de un visor del equipo ni sacar el archivo: `PdfRenderer` (no hay texto seleccionable). Compartirlo es un botón aparte
 * y lo decide el médico.
 */
@Composable
fun PreviewScreen(env: PhoneEnv, doc: PreviewDoc, onClose: () -> Unit) {
    PatientDataWindow()
    val context = LocalContext.current
    val nav = PhoneNav({}, onClose, {}, {}, {})
    val pages by load(doc.file) { renderDocument(doc.file, doc.mimeType) }
    Column(Modifier.fillMaxSize().testTag(PhoneTags.PREVIEW)) {
        DetailHeader(doc.title, nav) {
            PButton("Compartir", height = 40.dp, modifier = Modifier.padding(end = 8.dp)) {
                context.startActivity(Intent.createChooser(env.documents.shareIntent(doc.file, doc.mimeType), PhoneText.SHARE_TITLE))
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Loads(pages) { bitmaps ->
                if (bitmaps.isEmpty()) Message(PhoneText.SHARE_FAILED)
                bitmaps.forEachIndexed { index, bitmap ->
                    Image(
                        bitmap.asImageBitmap(),
                        null,
                        Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color.White).semantics { contentDescription = "${doc.title}, página ${index + 1} de ${bitmaps.size}" },
                        contentScale = ContentScale.FillWidth,
                    )
                }
            }
        }
    }
}

private const val RENDER_WIDTH = 1400
private const val MAX_PAGES = 30

fun renderDocument(file: File, mimeType: String): List<Bitmap> {
    if (mimeType != "application/pdf") return listOfNotNull(BitmapFactory.decodeFile(file.path))
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            return (0 until minOf(renderer.pageCount, MAX_PAGES)).map { index ->
                renderer.openPage(index).use { page ->
                    val height = RENDER_WIDTH * page.height / page.width
                    Bitmap.createBitmap(RENDER_WIDTH, height, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(AndroidColor.WHITE)
                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    }
}
