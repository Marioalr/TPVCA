package io.github.marioalr.tpvca.ui.tablet

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.marioalr.tpvca.R
import io.github.marioalr.tpvca.sync.engine.ClinicProfileRules
import io.github.marioalr.tpvca.sync.engine.LetterheadKind
import io.github.marioalr.tpvca.ui.theme.LocalTpvcColors
import java.io.InputStream
import java.time.Instant
import java.time.format.DateTimeFormatter

private val PAPER = Color(0xFFFFFFFF)
private val INK = Color(0xFF000000)
private val DOCTOR_BLUE = Color(0xFF0369A1)
private val PLACEHOLDER = Color(0xFF5A687D)
private val FOOTER = Color(0xFFF0F0F0)

/** Lee hasta [max] bytes de un archivo elegido; `null` si pesa más (no se carga entero un archivo enorme). */
private fun readCapped(context: Context, uri: Uri, max: Long): ByteArray? = context.contentResolver.openInputStream(uri)?.use { input: InputStream ->
    val bytes = input.readNBytesCompat(max + 1)
    if (bytes.size > max) null else bytes
}

private fun InputStream.readNBytesCompat(limit: Long): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (total < limit) {
        val n = read(buffer, 0, minOf(buffer.size.toLong(), limit - total).toInt())
        if (n < 0) break
        out.write(buffer, 0, n)
        total += n
    }
    return out.toByteArray()
}

// ================================================================== Médico y consultorio

/** Médico y consultorio (`ClinicProfileView`): los datos y el membrete a la izquierda, y a la derecha cómo salen en la receta y en el ticket. */
@Composable
internal fun ClinicProfileSection(workspace: TabletWorkspace) {
    val c = workspace.clinic
    val colors = LocalTpvcColors.current
    val context = LocalContext.current
    val pickCrest = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { c.chooseImage(LetterheadKind.Crest) { readCapped(context, it, ClinicProfileRules.MAX_IMAGE_BYTES.toLong()) } } }
    val pickHeader = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { c.chooseImage(LetterheadKind.Header) { readCapped(context, it, ClinicProfileRules.MAX_IMAGE_BYTES.toLong()) } } }
    BoxWithConstraints(Modifier.fillMaxSize().testTag(TabletTags.CLINIC)) {
        val wide = isWide(860.dp)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SectionHeader("Médico y consultorio", "Aparecen en recetas, tickets y cortes de caja") {
                TButton(c.actionText, c::toggleEditOrSave, kind = TButtonKind.Primary, icon = if (c.isEditing) TIcons.Save else TIcons.Edit, enabled = c.loaded && !c.saving && c.readingImages == 0, tag = TabletTags.CLINIC_ACTION)
            }
            c.status?.let { TNotice(it, if (c.isSuccess) colors.infoSubtle else colors.dangerSubtle, if (c.isSuccess) colors.infoText else colors.dangerText, tag = TabletTags.CLINIC_STATUS) }
            if (!c.loaded) {
                TCaption("Leyendo…")
                return@Column
            }
            val form: @Composable (Modifier) -> Unit = { m ->
                Column(m, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    TCard(Modifier.fillMaxWidth(), padding = 20.dp) {
                        THeading("Médico", Modifier.padding(bottom = 12.dp))
                        ProfileField(c.isEditing, c.doctorName, { c.doctorName = it }, "Nombre completo", "Ej. Dra. Ana López Pérez", "doctor")
                        FieldRows(2, gap = 12.dp, content = listOf(
                            { mod -> ProfileField(c.isEditing, c.professionalLicense, { c.professionalLicense = it }, "Cédula profesional", "", "cedula", mod, KeyboardType.Number) },
                            { mod -> ProfileField(c.isEditing, c.specialty, { c.specialty = it }, "Especialidad", "Medicina general", "especialidad", mod) },
                        ))
                        ProfileField(c.isEditing, c.university, { c.university = it }, "Universidad", "Ej. Universidad Autónoma de Ejemplo", "universidad")
                    }
                    TCard(Modifier.fillMaxWidth(), padding = 20.dp) {
                        THeading("Membrete de la receta")
                        TText("Se toman de su Excel al importarlo, o elíjalas aquí (PNG o JPG). Se guardan al presionar Guardar.", Modifier.padding(top = 4.dp, bottom = 12.dp), size = TType.detail, color = colors.textSecondary)
                        FieldRows(2, content = listOf(
                            { mod -> LetterheadChooser(mod, "Escudo / logotipo", "Arriba a la izquierda", c.crest, 72.dp, c.isEditing && !c.saving, { pickCrest.launch(arrayOf("image/png", "image/jpeg")) }, { c.clearImage(LetterheadKind.Crest) }, "escudo") },
                            { mod -> LetterheadChooser(mod, "Imagen del encabezado", "Arriba a la derecha (trazo de signos vitales)", c.header, 120.dp, c.isEditing && !c.saving, { pickHeader.launch(arrayOf("image/png", "image/jpeg")) }, { c.clearImage(LetterheadKind.Header) }, "encabezado") },
                        ))
                    }
                    TCard(Modifier.fillMaxWidth(), padding = 20.dp) {
                        THeading("Consultorio", Modifier.padding(bottom = 12.dp))
                        ProfileField(c.isEditing, c.clinicName, { c.clinicName = it }, "Nombre del consultorio", "", "consultorio")
                        ProfileField(c.isEditing, c.address, { c.address = it }, "Dirección", "", "direccion")
                        FieldRows(3, gap = 12.dp, content = listOf(
                            { mod -> ProfileField(c.isEditing, c.phone, { c.phone = it }, "Teléfono", "", "telefono", mod, KeyboardType.Phone) },
                            { mod -> ProfileField(c.isEditing, c.email, { c.email = it }, "Correo", "", "correo", mod, KeyboardType.Email) },
                            { mod -> ProfileField(c.isEditing, c.taxId, { c.taxId = it }, "RFC", "", "rfc", mod, capitalization = KeyboardCapitalization.Characters) },
                        ))
                    }
                    TCard(Modifier.fillMaxWidth(), padding = 20.dp) {
                        THeading("Notas al pie de la receta", Modifier.padding(bottom = 12.dp))
                        TField(c.additionalNotes, { c.additionalNotes = it }, readOnly = !c.isEditing, multiline = true, minHeight = 70.dp, placeholder = "Horario de atención, indicaciones generales…", description = "Notas al pie de la receta", tag = TabletTags.field("perfil-notas"))
                    }
                }
            }
            val preview: @Composable (Modifier) -> Unit = { m -> ProfilePreview(workspace, m) }
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
                    form(Modifier.weight(1.4f))
                    preview(Modifier.weight(1f))
                }
            } else {
                form(Modifier.fillMaxWidth())
                preview(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ProfileField(
    editing: Boolean,
    value: String,
    onChange: (String) -> Unit,
    label: String,
    placeholder: String,
    tag: String,
    modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Words,
) {
    // `ProfileField`: 12 de margen abajo, como en la PC
    TField(value, onChange, modifier.fillMaxWidth().padding(bottom = 12.dp), label = label, placeholder = placeholder, readOnly = !editing, keyboardType = keyboard, capitalization = capitalization, tag = TabletTags.field("perfil-$tag"))
}

/** Una imagen del membrete: la muestra en papel blanco, como sale impresa, con «Elegir…» y «Quitar». */
@Composable
private fun LetterheadChooser(modifier: Modifier, label: String, caption: String, bytes: ByteArray?, width: Dp, enabled: Boolean, onChoose: () -> Unit, onClear: () -> Unit, tag: String) {
    val colors = LocalTpvcColors.current
    Column(modifier) {
        FieldLabel(label)
        TCaption(caption, Modifier.padding(bottom = 6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(width, 72.dp).clip(RoundedCornerShape(6.dp)).background(PAPER).border(1.dp, colors["ThemeBorder"], RoundedCornerShape(6.dp)).padding(4.dp), contentAlignment = Alignment.Center) {
                PaperImage(bytes, Modifier.fillMaxSize())
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TButton("Elegir…", onChoose, enabled = enabled, tag = TabletTags.field("perfil-elegir-$tag"), description = "Elegir $label (PNG o JPG)")
                if (bytes != null) TButton("Quitar", onClear, kind = TButtonKind.Ghost, enabled = enabled, tag = TabletTags.field("perfil-quitar-$tag"), description = "Quitar $label")
            }
        }
    }
}

@Composable
private fun PaperImage(bytes: ByteArray?, modifier: Modifier) {
    // Una muestra chica: nunca la imagen entera en memoria (puede ser de 10 MB)
    val bitmap = remember(bytes) { bytes?.let { runCatching { decodeSmall(it) }.getOrNull() }?.asImageBitmap() }
    if (bitmap != null) Image(bitmap, null, modifier, contentScale = ContentScale.Fit)
}

/** La imagen a no más de unos 512 px de lado (`inSampleSize`). */
private fun decodeSmall(bytes: ByteArray): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 512 || bounds.outHeight / (sample * 2) >= 512) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}

/** «Así sale en la receta» (el encabezado y el pie, en papel blanco), «Vista previa de la receta» y «En tickets y cortes de caja». */
@Composable
private fun ProfilePreview(workspace: TabletWorkspace, modifier: Modifier) {
    val c = workspace.clinic
    val colors = LocalTpvcColors.current
    val context = LocalContext.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TText("Así sale en la receta", size = TType.caption, weight = FontWeight.Bold, color = colors.textSecondary)
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(PAPER).border(1.dp, colors["ThemeBorder"], RoundedCornerShape(8.dp)).padding(horizontal = 16.dp, vertical = 14.dp)
                .testTag(TabletTags.CLINIC_PREVIEW),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (c.crest != null) PaperImage(c.crest, Modifier.size(52.dp).padding(end = 10.dp))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    TText(c.previewDoctorName, size = 18.sp, color = if (c.doctorName.isNotBlank()) DOCTOR_BLUE else PLACEHOLDER, align = TextAlign.Center)
                    if (c.university.isNotBlank()) TText(c.university, Modifier.padding(top = 2.dp), size = 12.sp, color = INK, align = TextAlign.Center)
                    if (c.professionalLicense.isNotBlank()) TText("Cédula Profesional ${c.professionalLicense}", size = 12.sp, color = INK, align = TextAlign.Center)
                }
            }
            if (c.header != null) Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.CenterEnd) { PaperImage(c.header, Modifier.size(131.dp, 44.dp)) }
            if (c.phone.isNotBlank()) FooterLine(R.drawable.receta_whatsapp, c.phone)
            if (c.address.isNotBlank()) FooterLine(R.drawable.receta_ubicacion, c.address)
        }
        TButton("Vista previa de la receta", { c.openPreview(context) }, Modifier.fillMaxWidth(), icon = TIcons.File, tag = TabletTags.CLINIC_PRESCRIPTION_PREVIEW, description = "Vista previa de la receta: una receta de ejemplo con estos datos")
        TText("En tickets y cortes de caja", Modifier.padding(top = 8.dp), size = TType.caption, weight = FontWeight.Bold, color = colors.textSecondary)
        TCard(Modifier.fillMaxWidth(), padding = 20.dp) {
            TText(c.previewClinicName, weight = FontWeight.SemiBold, color = if (c.clinicName.isNotBlank()) colors.text else colors.textSecondary)
            if (c.specialty.isNotBlank()) TText(c.specialty, size = TType.detail)
            if (c.address.isNotBlank()) TCaption(c.address)
            if (c.phone.isNotBlank()) TCaption("Tel. ${c.phone}")
            if (c.email.isNotBlank()) TCaption(c.email)
        }
    }
}

@Composable
private fun FooterLine(icon: Int, text: String) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp).background(FOOTER).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(androidx.compose.ui.res.painterResource(icon), null, Modifier.size(14.dp).padding(end = 2.dp), tint = Color.Unspecified)
        TText(text, Modifier.padding(start = 6.dp), size = 12.sp, color = INK)
    }
}

// ================================================================== Respaldos y datos

private val MODIFIED = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

/** El nombre, el tamaño y la fecha de un archivo elegido (`ImportFileDetail`: «120 KB · modificado 12/11/2026 10:30»). */
private fun describe(context: Context, uri: Uri): Pair<String, String> {
    var name = uri.lastPathSegment?.substringAfterLast('/') ?: "archivo"
    var size: Long? = null
    var modified: Long? = null
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { i -> cursor.getString(i)?.takeIf { it.isNotBlank() }?.let { name = it } }
                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let { size = cursor.getLong(it) }
                cursor.getColumnIndex("last_modified").takeIf { it >= 0 && !cursor.isNull(it) }?.let { modified = cursor.getLong(it) }
            }
        }
    }
    val parts = ArrayList<String>()
    size?.let { parts += "${Math.rint(it / 1024.0).toLong()} KB" }
    modified?.takeIf { it > 0 }?.let { parts += "modificado " + Instant.ofEpochMilli(it).atZone(io.github.marioalr.tpvca.domain.Clinic.ZONE).format(MODIFIED) }
    return name to parts.joinToString(" · ")
}

/**
 * Respaldos y datos (`DataManagementView`) en la tablet: solo «Importar catálogo de Excel». Los respaldos de la base, restaurar una base completa y el archivo de laboratorio son de la PC, como en la
 * PC con otro rol (`CanManageBackups`).
 */
@Composable
internal fun DataSection(workspace: TabletWorkspace) {
    val data = workspace.data
    val colors = LocalTpvcColors.current
    val context = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val (name, detail) = describe(context, uri)
        data.open(ImportFile(name, detail) { readCapped(context, uri, io.github.marioalr.tpvca.domain.imports.Xlsx.MAX_FILE_BYTES) ?: throw io.github.marioalr.tpvca.domain.imports.XlsxException(io.github.marioalr.tpvca.domain.imports.Xlsx.TOO_BIG) })
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 18.dp).testTag(TabletTags.DATA), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionHeader("Respaldos y datos", "Copias de seguridad, importar el catálogo y restaurar información")
        TCard(Modifier.widthIn(max = 640.dp).fillMaxWidth(), padding = 20.dp) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(colors.infoSubtle), contentAlignment = Alignment.Center) { Icon(TIcons.File, null, tint = colors.infoText) }
            THeading("Importar catálogo de Excel", Modifier.padding(top = 12.dp))
            TText(
                "Medicamentos, plantillas de receta, dosis pediátricas, datos del médico y las imágenes del membrete de la receta (si aún no tiene) desde la hoja de Excel (.xlsx) o un CSV. No se borra nada, pero lo que ya existe se reemplaza (medicamentos, dosis, el médico y el expediente que traiga la receta): antes de importar se muestra qué cambia. La tablet no hace respaldo; la PC sí.",
                Modifier.padding(top = 4.dp, bottom = 14.dp), size = TType.detail, color = colors.textSecondary,
            )
            TButton("Elegir archivo…", {
                pick.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "text/csv", "text/comma-separated-values", "text/plain", "application/octet-stream"))
            }, icon = TIcons.File, enabled = !data.importing && !data.preparing, tag = TabletTags.DATA_CHOOSE)
        }
        TCaption("Respaldar y restaurar la base se hace en la PC.")
    }
}

/** La hoja de importar (`IsImportSheetOpen`): el archivo, qué va a pasar, «Importar» y, al terminar, lo que se importó. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ImportSheet(workspace: TabletWorkspace, file: ImportFile) {
    val data = workspace.data
    val colors = LocalTpvcColors.current
    val outcome = data.outcome
    SheetFrame {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors["ThemeCard"]).testTag(TabletTags.IMPORT_SHEET).padding(horizontal = 24.dp, vertical = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                THeading("Importar catálogo de Excel", Modifier.weight(1f), size = TType.heading)
                TIconButton(TIcons.Close, "Cerrar", data::close, enabled = !data.importing && !data.preparing, tag = TabletTags.PANEL_CLOSE)
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 14.dp).clip(RoundedCornerShape(10.dp)).background(colors.surfaceSubtle).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(TIcons.File, null, Modifier.padding(end = 10.dp), tint = colors.textSecondary)
                Column {
                    TText(file.name, weight = FontWeight.SemiBold, maxLines = 1)
                    if (file.detail.isNotEmpty()) TCaption(file.detail)
                }
            }
            val ready = data.ready
            if (outcome == null) {
                if (ready == null) {
                    TText("Qué va a pasar", Modifier.padding(bottom = 8.dp), size = TType.caption, weight = FontWeight.Bold, color = colors.textSecondary)
                    for ((i, step) in DataController.STEPS.withIndex()) {
                        Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.Top) {
                            Box(Modifier.size(22.dp).clip(RoundedCornerShape(11.dp)).background(colors.infoSubtle), contentAlignment = Alignment.Center) { TText("${i + 1}", size = TType.caption, weight = FontWeight.Bold, color = colors.infoText) }
                            TText(step, Modifier.padding(start = 10.dp), size = TType.detail)
                        }
                    }
                    TText("Sincronizando y revisando el archivo…", Modifier.padding(top = 4.dp), size = TType.detail, color = colors.textSecondary)
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp), color = colors.accentStrong)
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) { TButton("Cancelar", data::close, kind = TButtonKind.Ghost, enabled = false) }
                } else {
                    TText("Qué va a cambiar", Modifier.padding(bottom = 8.dp), size = TType.caption, weight = FontWeight.Bold, color = colors.textSecondary)
                    Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()).testTag(TabletTags.IMPORT_PREVIEW), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (line in ready.preview.lines) TText("• $line", size = TType.detail)
                    }
                    TNotice(io.github.marioalr.tpvca.domain.imports.ExcelImport.NO_BACKUP_WARNING, colors.warningSubtle, colors.warningText, Modifier.padding(top = 12.dp), tag = TabletTags.IMPORT_WARNING)
                    if (data.importing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp), color = colors.accentStrong)
                    FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        TButton("Cancelar", data::close, kind = TButtonKind.Ghost, enabled = !data.importing)
                        TButton(if (data.importing) "Importando…" else "Importar", data::execute, kind = TButtonKind.Primary, enabled = !data.importing, tag = TabletTags.IMPORT_RUN)
                    }
                }
            } else {
                TNotice(outcome.message, if (outcome.success) colors.successSubtle else colors.dangerSubtle, if (outcome.success) colors.successText else colors.dangerText, tag = TabletTags.IMPORT_RESULT)
                for (line in outcome.lines) TText("• $line", Modifier.padding(top = 6.dp), size = TType.detail)
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) {
                    TButton("Cerrar", data::close, kind = TButtonKind.Primary, tag = TabletTags.IMPORT_CLOSE)
                }
            }
        }
    }
}
