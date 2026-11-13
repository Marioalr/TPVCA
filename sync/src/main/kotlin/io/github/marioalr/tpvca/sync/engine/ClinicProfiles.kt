package io.github.marioalr.tpvca.sync.engine

import java.security.MessageDigest
import java.util.Locale

/** Los datos del médico y del consultorio (`ClinicProfile` de la PC) como los guarda «Médico y consultorio». */
data class ProfileValues(
    val doctorName: String = "",
    val professionalLicense: String = "",
    val specialty: String = "",
    val university: String = "",
    val clinicName: String = "",
    val address: String = "",
    val phone: String = "",
    val email: String = "",
    val taxId: String = "",
    val additionalNotes: String = "",
) {
    /** Tiene lo mínimo (`ToggleEditOrSave`): el nombre del médico o el del consultorio. */
    val hasData: Boolean get() = doctorName.isNotBlank() || clinicName.isNotBlank()

    fun columns(): Map<String, String> = mapOf(
        "DoctorName" to doctorName,
        "ProfessionalLicense" to professionalLicense,
        "Specialty" to specialty,
        "University" to university,
        "ClinicName" to clinicName,
        "Address" to address,
        "Phone" to phone,
        "Email" to email,
        "TaxId" to taxId,
        "AdditionalNotes" to additionalNotes,
    )

    companion object {
        fun of(row: Map<String, Any?>?): ProfileValues = ProfileValues(
            doctorName = row?.get("DoctorName") as String? ?: "",
            professionalLicense = row?.get("ProfessionalLicense") as String? ?: "",
            specialty = row?.get("Specialty") as String? ?: "",
            university = row?.get("University") as String? ?: "",
            clinicName = row?.get("ClinicName") as String? ?: "",
            address = row?.get("Address") as String? ?: "",
            phone = row?.get("Phone") as String? ?: "",
            email = row?.get("Email") as String? ?: "",
            taxId = row?.get("TaxId") as String? ?: "",
            additionalNotes = row?.get("AdditionalNotes") as String? ?: "",
        )
    }
}

/** Una imagen del membrete elegida aquí: sus bytes, o `null` para quitarla («Quitar»). */
class LetterheadChange(val bytes: ByteArray?)

/** El escudo (arriba a la izquierda) o la imagen del encabezado (a la derecha), como `LetterheadImageKind` de la PC. */
enum class LetterheadKind(val column: String, val cloudKind: String, val editedKey: String) {
    Crest("CrestImageHash", "crest", "Letterhead:CrestEditedHere"),
    Header("HeaderImageHash", "header", "Letterhead:HeaderEditedHere"),
}

/** Las reglas del membrete y del perfil que comparten la PC y la tablet (`ClinicProfileService`, `LetterheadHtml.ImageExtension`, paso 8.12 y revisión 19). */
object ClinicProfileRules {
    /** `MaxLetterheadImageBytes`. */
    const val MAX_IMAGE_BYTES = 10 * 1024 * 1024

    /** «.png», «.jpg» o `null` si no es PNG ni JPG (`ImageExtension`: por sus primeros bytes). */
    fun imageExtension(content: ByteArray?): String? {
        if (content == null || content.size < 8) return null
        if (content[0] == 0x89.toByte() && content[1] == 0x50.toByte() && content[2] == 0x4E.toByte() && content[3] == 0x47.toByte()) return ".png"
        if (content[0] == 0xFF.toByte() && content[1] == 0xD8.toByte() && content[2] == 0xFF.toByte()) return ".jpg"
        return null
    }

    fun mimeType(content: ByteArray): String = if (imageExtension(content) == ".png") "image/png" else "image/jpeg"

    /** `HashImage`: el SHA-256 en hexadecimal en minúsculas. */
    fun hash(content: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(Locale.ROOT, it) }

    /** El texto de la PC para una imagen que no se puede usar; `null` si sirve. */
    fun imageProblem(content: ByteArray): String? = when {
        imageExtension(content) == null -> "El archivo no es una imagen PNG o JPG."
        content.size > MAX_IMAGE_BYTES -> "La imagen pesa más de 10 MB; elija una más ligera."
        else -> null
    }

    /** Un perfil con datos: el nombre del médico o el del consultorio (`IsConfigured` de la PC, que la tablet no guarda aparte). */
    fun hasData(row: Map<String, Any?>?): Boolean = !(row?.get("DoctorName") as String?).isNullOrBlank() || !(row?.get("ClinicName") as String?).isNullOrBlank()
}
