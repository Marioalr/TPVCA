package io.github.marioalr.tpvca.domain.documents

import io.github.marioalr.tpvca.domain.consultation.LabCatalog
import io.github.marioalr.tpvca.domain.model.ClinicProfile
import io.github.marioalr.tpvca.domain.model.LabStudy
import io.github.marioalr.tpvca.domain.model.Patient
import java.time.LocalDate

/** Los estudios de un área en la hoja: "Hematología" y sus estudios. */
data class LabOrderSheetGroup(val area: String, val studies: List<String>)

/**
 * La solicitud de estudios de laboratorio tal como sale en papel (`Models/LabOrderSheet.cs`, desde v1.25.0 de la PC). Usa el membrete de la receta (escudo, nombre, universidad y
 * cédula; teléfono y domicilio al pie) y su mismo papel: media carta horizontal, o carta vertical si no cabe. Debajo: Paciente · Edad · Fecha, Sexo · Diagnóstico, el título, los
 * estudios por área y las indicaciones para el paciente.
 */
data class LabOrderSheet(
    /** Membrete, paciente, fecha y pie: los de la receta. */
    val letterhead: PrescriptionSheet,
    val sex: String,
    val diagnosis: String,
    val groups: List<LabOrderSheetGroup>,
    val indications: String,
) {
    val studyCount: Int get() = groups.sumOf { it.studies.size }

    companion object {
        const val TITLE = "Solicitud de estudios de laboratorio"

        /** El diagnóstico que se guarda cuando el médico no escribió ninguno: en la hoja queda en blanco. */
        const val PLACEHOLDER_DIAGNOSIS = "Consulta Médica General"

        /** Con más renglones que estos, los estudios van en dos columnas (`LetterheadLayout.SingleColumnLines`). */
        const val SINGLE_COLUMN_LINES = 9

        fun create(patient: Patient, studies: List<LabStudy>, indications: String?, profile: ClinicProfile?, diagnosis: String?, date: LocalDate): LabOrderSheet {
            val groups = studies
                .filter { it.name.isNotBlank() }
                .groupBy { it.area.trim().ifEmpty { LabCatalog.OTHER_AREA } }
                .entries
                .sortedBy { LabCatalog.areaOrder(it.key) }
                .map { (area, list) -> LabOrderSheetGroup(area, list.map { it.name.trim() }) }
            val dx = diagnosis.orEmpty().trim()
            return LabOrderSheet(
                letterhead = PrescriptionSheet.create(patient, null, profile, null, date),
                sex = sexOf(patient.gender),
                diagnosis = if (dx == PLACEHOLDER_DIAGNOSIS) "" else dx,
                groups = groups,
                indications = indications.orEmpty().trim(),
            )
        }

        /** "Femenino" o "Masculino"; en blanco si no se especificó. */
        fun sexOf(gender: String?): String {
            val g = gender.orEmpty().trim()
            return if (g.isEmpty() || g.equals("No especificado", ignoreCase = true)) "" else g
        }

        /**
         * Una o dos columnas de áreas (`LabOrderHtmlBuilder.SplitInColumns`): con más de [SINGLE_COLUMN_LINES] renglones y dos áreas o más, se parte en dos sin partir un área,
         * pasando a la segunda cuando la primera ya llegó a la mitad (siempre queda algo para la segunda).
         */
        fun splitInColumns(groups: List<LabOrderSheetGroup>): List<List<LabOrderSheetGroup>> {
            fun lines(g: LabOrderSheetGroup) = 1 + g.studies.size
            val total = groups.sumOf(::lines)
            if (total <= SINGLE_COLUMN_LINES || groups.size < 2) return listOf(groups)
            val first = ArrayList<LabOrderSheetGroup>()
            var used = 0
            for (group in groups) {
                if (first.isNotEmpty() && used + lines(group) / 2.0 > total / 2.0) break
                first += group
                used += lines(group)
            }
            if (first.size == groups.size) first.removeAt(first.size - 1)
            return listOf(first, groups.drop(first.size))
        }
    }
}
