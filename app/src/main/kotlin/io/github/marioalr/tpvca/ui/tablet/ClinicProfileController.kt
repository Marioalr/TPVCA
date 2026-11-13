package io.github.marioalr.tpvca.ui.tablet

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.marioalr.tpvca.R
import io.github.marioalr.tpvca.documents.PrescriptionImages
import io.github.marioalr.tpvca.documents.PrescriptionPdf
import io.github.marioalr.tpvca.domain.documents.PrescriptionSheet
import io.github.marioalr.tpvca.domain.model.ClinicProfile
import io.github.marioalr.tpvca.domain.model.Patient
import io.github.marioalr.tpvca.domain.model.PrescriptionItem
import io.github.marioalr.tpvca.sync.engine.ClinicProfileRules
import io.github.marioalr.tpvca.sync.engine.LetterheadChange
import io.github.marioalr.tpvca.sync.engine.LetterheadKind
import io.github.marioalr.tpvca.sync.engine.ProfileValues
import io.github.marioalr.tpvca.sync.engine.WriteEngine
import io.github.marioalr.tpvca.sync.engine.WriteRefused
import io.github.marioalr.tpvca.ui.agenda.AgendaController
import io.github.marioalr.tpvca.ui.phone.PreviewDoc
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * «Médico y consultorio» (`ClinicProfileViewModel` de la PC): los datos que salen en recetas, tickets y cortes de caja, y el membrete de la receta. Se ven bloqueados con «Editar»; «Guardar» los
 * escribe juntos (`WriteEngine.saveClinicProfile`) con una marca nueva, y suben con `profile.write` en la siguiente sincronización (las imágenes antes, con `asset.*`).
 *
 * - **Lo de la pantalla no se pisa:** mientras se edita, lo que baja no cambia los campos; sin editar, se vuelve a leer.
 * - Un guardado en curso no deja guardar otra vez ni cerrar lo que se escribe.
 * - Las letras de la receta de la PC son las instaladas en Windows y se guardan en cada computadora; la receta de Android usa las suyas, así que esa tarjeta no está.
 */
class ClinicProfileController internal constructor(private val workspace: TabletWorkspace, private val scope: CoroutineScope, private val io: CoroutineDispatcher) {
    private val env get() = workspace.env

    var doctorName by mutableStateOf("")
    var professionalLicense by mutableStateOf("")
    var specialty by mutableStateOf("")
    var university by mutableStateOf("")
    var clinicName by mutableStateOf("")
    var address by mutableStateOf("")
    var phone by mutableStateOf("")
    var email by mutableStateOf("")
    var taxId by mutableStateOf("")
    var additionalNotes by mutableStateOf("")

    var loaded by mutableStateOf(false)
        private set
    var isEditing by mutableStateOf(false)
        private set
    var actionText by mutableStateOf("Guardar")
        private set
    var status by mutableStateOf<String?>(null)
        private set
    var isSuccess by mutableStateOf(true)
        private set
    var saving by mutableStateOf(false)
        private set

    /** Cuántas imágenes elegidas se están leyendo: mientras tanto «Guardar» espera (si no, la imagen se perdería con un «guardado» en pantalla). */
    var readingImages by mutableStateOf(0)
        private set

    /** Las imágenes que se ven (las guardadas o las recién elegidas). */
    var crest by mutableStateOf<ByteArray?>(null)
        private set
    var header by mutableStateOf<ByteArray?>(null)
        private set

    /** Cambio de imagen pendiente hasta «Guardar»: la elegida, o quitarla. */
    private var pendingCrest: LetterheadChange? = null
    private var pendingHeader: LetterheadChange? = null
    private var request = 0

    private fun engine(): WriteEngine = env.writes() ?: throw WriteRefused(WriteRefused.Reason.NOT_APPROVED, AgendaController.NOT_READY)

    /** «Sin configurar…», el médico o el consultorio (`Summary`). */
    val summary: String
        get() = when {
            doctorName.isNotBlank() -> doctorName.trim()
            clinicName.isNotBlank() -> clinicName.trim()
            else -> "Sin configurar (sale en recetas y tickets)"
        }

    val previewDoctorName: String get() = doctorName.trim().ifEmpty { "Nombre del médico" }
    val previewClinicName: String get() = clinicName.trim().ifEmpty { "Nombre del consultorio" }

    /** `LoadFromProfile`: lo guardado, bloqueado con «Editar»; sin nada guardado, abierto con «Guardar». No toca lo que se está editando. */
    fun load(force: Boolean = false) {
        if (isEditing && !force) return
        val ticket = ++request
        scope.launch {
            val read = withContext(io) {
                runCatching {
                    val row = env.writes()?.clinicProfile()
                    val letterhead = env.phone.reader.letterhead()
                    Triple(ProfileValues.of(row), letterhead.crest?.readBytes(), letterhead.header?.readBytes())
                }
            }
            if (ticket != request || (isEditing && !force)) return@launch
            val (values, crestBytes, headerBytes) = read.getOrElse {
                status = "No se pudo leer el perfil de este equipo."
                isSuccess = false
                return@launch
            }
            show(values)
            crest = crestBytes
            header = headerBytes
            pendingCrest = null
            pendingHeader = null
            // Lo que bajó mientras se veía la sección: los datos de ahora, sin cambiar el aviso (por ejemplo el de «guardados»)
            if (loaded && !force) return@launch
            loaded = true
            if (values.hasData) {
                isEditing = false
                actionText = "Editar"
                status = "Datos guardados y activos en recetas, tickets y cortes de caja."
            } else {
                isEditing = true
                actionText = "Guardar"
                status = "Ingrese los datos del médico y consultorio y presione Guardar."
            }
            isSuccess = true
        }
    }

    private fun show(values: ProfileValues) {
        doctorName = values.doctorName
        professionalLicense = values.professionalLicense
        specialty = values.specialty
        university = values.university
        clinicName = values.clinicName
        address = values.address
        phone = values.phone
        email = values.email
        taxId = values.taxId
        additionalNotes = values.additionalNotes
    }

    /** Lo de la pantalla, sin espacios a los lados (lo que se guardaría con «Guardar»). */
    private fun screenValues() = ProfileValues(
        doctorName.trim(), professionalLicense.trim(), specialty.trim(), university.trim(), clinicName.trim(), address.trim(), phone.trim(), email.trim(), taxId.trim(), additionalNotes.trim(),
    )

    /** «Editar» abre los campos; «Guardar» revisa lo mínimo y guarda todo junto (`ToggleEditOrSave`). */
    fun toggleEditOrSave() {
        if (saving || readingImages > 0) return
        if (!isEditing) {
            // Una lectura que iba en camino ya no deja la pantalla bloqueada ni cambia lo que se va a editar
            request++
            isEditing = true
            actionText = "Guardar"
            status = "Modo de edición activado. Realice los cambios necesarios y presione Guardar."
            isSuccess = true
            return
        }
        val values = screenValues()
        if (!values.hasData) {
            status = "Por favor ingrese al menos el Nombre del Médico o el Nombre del Consultorio."
            isSuccess = false
            return
        }
        val crestChange = pendingCrest
        val headerChange = pendingHeader
        saving = true
        // Lo que bajara mientras se guarda ya no pisa la pantalla
        request++
        scope.launch {
            try {
                val imageError = withContext(io) { engine().saveClinicProfile(values, crestChange, headerChange) }
                env.noteLocalWrite()
                workspace.changed()
                pendingCrest = null
                pendingHeader = null
                isEditing = false
                actionText = "Editar"
                status = if (imageError == null) "✓ Datos del consultorio y médico guardados exitosamente." else "Datos guardados, pero la imagen del membrete no: $imageError"
                isSuccess = imageError == null
            } catch (e: Exception) {
                status = "No se pudieron guardar los datos: ${workspace.problem(e)}"
                isSuccess = false
            } finally {
                saving = false
            }
        }
    }

    /** «Elegir…»: una imagen PNG o JPG de hasta 10 MB; queda en la receta al presionar «Guardar». [read] lee el archivo (fuera del hilo principal). */
    fun chooseImage(kind: LetterheadKind, read: () -> ByteArray?) {
        if (!isEditing || saving) return
        readingImages++
        scope.launch {
            val bytes = try {
                withContext(io) { runCatching(read).getOrNull() }
            } finally {
                readingImages--
            }
            if (!isEditing) return@launch
            if (bytes == null || ClinicProfileRules.imageProblem(bytes) != null) {
                status = "Elija una imagen PNG o JPG de hasta 10 MB."
                isSuccess = false
                return@launch
            }
            when (kind) {
                LetterheadKind.Crest -> {
                    pendingCrest = LetterheadChange(bytes)
                    crest = bytes
                }
                LetterheadKind.Header -> {
                    pendingHeader = LetterheadChange(bytes)
                    header = bytes
                }
            }
            status = "Imagen elegida: queda en la receta al presionar Guardar."
            isSuccess = true
        }
    }

    /** «Quitar»: la receta sale sin esa imagen (también en los demás equipos), al presionar «Guardar». */
    fun clearImage(kind: LetterheadKind) {
        if (!isEditing || saving) return
        when (kind) {
            LetterheadKind.Crest -> {
                pendingCrest = LetterheadChange(null)
                crest = null
            }
            LetterheadKind.Header -> {
                pendingHeader = LetterheadChange(null)
                header = null
            }
        }
    }

    /**
     * «Vista previa de la receta»: la receta de ejemplo (un paciente y tres medicamentos inventados, como `BuildSamplePrescription`) con los datos y el membrete de esta pantalla, también lo que aún no
     * se guarda, en el PDF que se comparte.
     */
    fun openPreview(context: Context) {
        val values = screenValues()
        val crestBytes = crest
        val headerBytes = header
        val documents = env.phone.documents
        val ticket = documents.ticket()
        val today = workspace.today()
        scope.launch {
            val file = withContext(io) {
                runCatching {
                    val profile = ClinicProfile(values.clinicName, values.doctorName, values.professionalLicense, values.university, values.phone, values.address, values.specialty)
                    val sheet = PrescriptionSheet.create(SAMPLE_PATIENT, SAMPLE_ITEMS, profile, null, today)
                    val images = PrescriptionImages(
                        crestBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) },
                        headerBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) },
                        BitmapFactory.decodeResource(context.resources, R.drawable.receta_whatsapp),
                        BitmapFactory.decodeResource(context.resources, R.drawable.receta_ubicacion),
                    )
                    documents.writeView(ticket, "Receta de ejemplo.pdf") { PrescriptionPdf.write(sheet, images, it) }
                }.getOrNull()
            }
            if (file == null) workspace.info = "No se pudo armar la receta de ejemplo." else workspace.preview = PreviewDoc("Receta de ejemplo", file, "application/pdf")
        }
    }

    /** Al irse de la sección o de Configuración: lo que no se guardó se pierde, como en la PC al volver a la sección. */
    internal fun left() {
        if (saving) return
        request++
        isEditing = false
        loaded = false
    }

    internal fun clearMemory() {
        request++
        show(ProfileValues())
        crest = null
        header = null
        pendingCrest = null
        pendingHeader = null
        loaded = false
        isEditing = false
        status = null
    }

    private companion object {
        val SAMPLE_PATIENT = Patient(
            fullName = "Paciente de Ejemplo Pérez",
            storedAge = 34,
            allergies = "Sulfas",
            vitalSigns = "TA: 118/76 mmHg | Temp: 36.6 °C | Peso: 62 kg | Talla: 1.62 m | SpO2: 98 %",
        )
        val SAMPLE_ITEMS = listOf(
            PrescriptionItem("Paracetamol 500 mg tabletas", "Tomar 1 tableta vía oral cada 8 horas por 3 días, solo si hay fiebre o dolor", "Caja con 20 tabletas"),
            PrescriptionItem("Loratadina 10 mg tabletas", "Tomar 1 tableta vía oral cada 24 horas por 5 días", "Caja con 10 tabletas"),
            PrescriptionItem("Ambroxol 30 mg/5 ml jarabe", "Tomar 5 ml vía oral cada 8 horas por 5 días", "Frasco con 120 ml"),
        )
    }
}
