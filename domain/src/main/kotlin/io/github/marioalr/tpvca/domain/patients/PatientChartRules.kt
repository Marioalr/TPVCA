package io.github.marioalr.tpvca.domain.patients

import io.github.marioalr.tpvca.domain.model.Appointment
import io.github.marioalr.tpvca.domain.model.Consultation
import io.github.marioalr.tpvca.domain.model.Patient
import io.github.marioalr.tpvca.domain.search.TextSearch
import io.github.marioalr.tpvca.domain.text.NetDecimal
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/** Una sección de antecedentes de la ficha: [summary] es lo que se ve cerrada y [detail] los datos, uno por renglón. */
data class AntecedentSection(val key: String, val title: String, val summary: String, val detail: String) {
    val isEmpty: Boolean get() = detail.isBlank()
}

enum class TimelineKind { Consultation, Appointment }

data class TimelineLine(val label: String, val value: String)

/** Una tarjeta del historial: una consulta o una cita. [detail] es lo que revisa la búsqueda (consulta) o "hora · estado" (cita). */
data class TimelineEntry(
    val kind: TimelineKind,
    val date: LocalDateTime,
    val title: String,
    val detail: String,
    val side: String = "",
    val lines: List<TimelineLine> = emptyList(),
)

/** Un signo de «Lo esencial»: su valor y la tendencia contra la medición anterior. */
data class VitalSummary(val label: String, val value: String, val trend: String, val isUp: Boolean)

/** Los textos de arriba de la gráfica de signos ("--" sin dato). */
data class LatestVitalTexts(val weight: String, val bloodPressure: String, val glucose: String, val bmi: String) {
    companion object {
        val NONE = LatestVitalTexts("--", "--", "--", "--")
    }
}

/**
 * La ficha del paciente (`Services/Rules/PatientChartRules.cs`): antecedentes NOM-004, historial, «Lo esencial» y los textos de
 * la gráfica. Vectores: `chart-antecedents.json`, `chart-timeline.json`, `latest-vitals.json` y `vitals-evolution.json`.
 * La ficha muestra la fecha del historial como "d MMM" de es-MX: ese texto depende de los datos de la cultura y no va en los
 * vectores.
 */
object PatientChartRules {
    /** Las secciones de antecedentes, con "hoy" para la edad (pediátrico y gineco-obstétricos). */
    fun buildAntecedentSections(p: Patient, today: LocalDate): List<AntecedentSection> {
        fun item(label: String, value: String?): String? = if (value.isNullOrBlank()) null else "$label: ${value.trim()}"
        fun make(key: String, title: String, vararg items: String?): AntecedentSection {
            val filled = items.filterNotNull().filter { it.isNotBlank() }
            val detail = filled.joinToString("\n")
            val summary = if (filled.isEmpty()) {
                "Sin registrar"
            } else {
                filled.take(2).map { if (it.contains(": ")) it.split(": ", limit = 2)[1] else it }
                    .filter { it.isNotBlank() }.joinToString(" · ") { it.trim() }
            }
            return AntecedentSection(key, title, summary, detail)
        }

        val pediatric = p.isPediatric(today)
        val sections = mutableListOf(
            make("Heredofamiliares", "Heredofamiliares", p.familyHistory),
            make(
                "Patologicos", "Personales patológicos",
                item("Alergias", p.allergySummary),
                item("Enfermedades crónicas", p.chronicDiseases),
                item("Cirugías", p.surgeries),
                item("Hospitalizaciones", p.hospitalizations),
                item("Transfusiones", p.transfusions),
                if (pediatric) null else item("Vacunación", p.immunizationSchedule),
            ),
        )

        if (p.showsGynecoObstetric(today)) {
            val formula = if (p.pregnanciesCount + p.cesareansCount + p.abortionsCount > 0) {
                "G${p.pregnanciesCount} C${p.cesareansCount} A${p.abortionsCount}"
            } else {
                null
            }
            sections.add(
                make(
                    "Gineco", "Gineco-obstétricos",
                    item("Gestas / cesáreas / abortos", formula),
                    item("Menarca", if (p.menarcheAge.isBlank()) null else "${p.menarcheAge} años"),
                    item("Ciclo", p.periodDuration),
                    item("Papanicolaou", p.papSmear),
                    item("Inicio de vida sexual", p.sexualOnsetAge),
                    if (p.isCurrentlyPregnant) "Embarazo actual" + (if (p.gestationalWeeks.isBlank()) "" else ": ${p.gestationalWeeks} semanas") else null,
                ),
            )
        }

        if (pediatric) {
            sections.add(make("Desarrollo", "Desarrollo y vacunas", item("Desarrollo", p.developmentalMilestones), item("Vacunación", p.immunizationSchedule)))
        }

        sections.add(
            make(
                "NoPatologicos", "No patológicos",
                item("Ocupación", p.occupation),
                item("Alimentación", p.diet),
                item("Vivienda", p.housingConditions),
                item("Animales", p.zoonosis),
                item("Religión", p.religion),
            ),
        )
        sections.add(
            make(
                "Contacto", "Contacto y emergencia",
                item("Teléfono", p.formattedPhone),
                item("Correo", p.email),
                item("Domicilio", p.address),
                if (p.emergencyContactName.isBlank()) null else "Emergencia: ${p.emergencyContactName.trim()} ${p.formattedEmergencyContactPhone}".trim(),
            ),
        )
        return sections
    }

    /**
     * El historial: las consultas (título, folio y líneas) y las citas (en su día y su hora). Por día de la más nueva a la más
     * vieja; en un día, las consultas antes que las citas; en cada grupo, de la hora más tarde a la más temprana.
     */
    fun buildTimeline(consultations: Iterable<Consultation>, appointments: Iterable<Appointment>): List<TimelineEntry> {
        val entries = mutableListOf<Pair<TimelineEntry, Int>>()
        for (c in consultations) {
            val title = when {
                c.diagnosis.isNotBlank() -> c.diagnosis.trim()
                c.chiefComplaint.isBlank() -> "Consulta"
                else -> c.chiefComplaint.trim()
            }
            val lines = mutableListOf<TimelineLine>()
            fun add(label: String, value: String?) {
                if (!value.isNullOrBlank()) lines.add(TimelineLine(label, firstLine(value)))
            }
            if (c.diagnosis.isNotBlank()) add("Motivo", c.chiefComplaint)
            add("Signos", c.vitalSigns.replace(" | ", " · "))
            add("Receta", c.prescriptions.map { it.medicineName.trim() }.filter { it.isNotEmpty() }.joinToString(" · "))
            add("Laboratorio", c.labStudies.joinToString(" · "))
            add("Plan", c.treatmentPlan)

            val searchText = listOf(
                c.chiefComplaint, c.systemsReview, c.physicalExamination, c.vitalSigns, c.treatmentPlan, c.notes,
                c.prescriptions.joinToString("\n") { "${it.medicineName} ${it.presentation.orEmpty()} ${it.dosage.orEmpty()}" },
                c.labStudies.joinToString("\n"), c.labIndications,
            ).filter { it.isNotBlank() }.joinToString("\n")

            entries.add(TimelineEntry(TimelineKind.Consultation, c.date, title, searchText, c.folio, lines) to 0)
        }
        for (a in appointments) {
            val time = PatientSummaryRules.timeOfDay(a.time)
            val whenAt = a.date.atStartOfDay().plus(time)
            entries.add(TimelineEntry(TimelineKind.Appointment, whenAt, if (a.reason.isBlank()) "Cita" else a.reason.trim(), "${a.time.orEmpty()} · ${a.status}") to 1)
        }
        return entries
            .sortedWith(
                compareByDescending<Pair<TimelineEntry, Int>> { it.first.date.toLocalDate() }
                    .thenBy { it.second }
                    .thenByDescending { it.first.date },
            )
            .map { it.first }
    }

    /**
     * Toda la nota de una consulta, sin recortar: lo que se ve al abrirla. [buildTimeline] solo trae el primer renglón de cada cosa, cortado a 160 caracteres.
     */
    fun fullNote(c: Consultation): List<TimelineLine> {
        val lines = mutableListOf<TimelineLine>()
        fun add(label: String, value: String?) {
            val text = value.orEmpty().replace("\r\n", "\n").trim()
            if (text.isNotEmpty()) lines.add(TimelineLine(label, text))
        }
        add("Motivo", c.chiefComplaint)
        add("Interrogatorio", c.systemsReview)
        add("Signos", c.vitalSigns.replace(" | ", " · "))
        add("Exploración", c.physicalExamination)
        add("Diagnóstico", c.diagnosis)
        add(
            "Receta",
            c.prescriptions.filter { it.medicineName.isNotBlank() }.joinToString("\n") { p ->
                p.medicineName.trim() + p.sheetDetail + if (p.dosage.isNullOrBlank()) "" else ": " + p.dosage.trim()
            },
        )
        add("Laboratorio", c.labStudies.joinToString(" · "))
        add("Indicaciones de laboratorio", c.labIndications)
        add("Plan", c.treatmentPlan)
        add("Nota", c.notes)
        return lines
    }

    /** El primer renglón no vacío, sin espacios a los lados; con más de 160 caracteres, los primeros 157 y "…". */
    fun firstLine(text: String): String {
        val line = text.replace("\r\n", "\n").split('\n').map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
        return if (line.length > 160) line.substring(0, 157).trimEnd() + "…" else line
    }

    /** Solo las consultas ("Consultas"), solo las citas ("Citas") o todo, con lo que coincide con [search]. */
    fun filterTimeline(entries: Iterable<TimelineEntry>, filter: String?, search: String?): List<TimelineEntry> {
        val kind = when (filter) {
            "Consultas" -> TimelineKind.Consultation
            "Citas" -> TimelineKind.Appointment
            else -> null
        }
        val query = TextSearch.normalize(search)
        return entries.filter { (kind == null || it.kind == kind) && TextSearch.matches(query, it.title, it.detail) }
    }

    /**
     * «Lo esencial»: T/A (la última con sistólica y diastólica contra la anterior), peso, IMC y glucosa (el último valor y la
     * diferencia con el anterior). Formato invariante con el punto medio hacia arriba.
     */
    fun buildLatestVitals(points: List<VitalSignsRules.EvolutionPoint>?): List<VitalSummary> {
        val list = points.orEmpty()
        if (list.isEmpty()) return emptyList()

        fun summary(label: String, value: (VitalSignsRules.EvolutionPoint) -> BigDecimal?, format: (BigDecimal) -> String, unit: String): VitalSummary? {
            val withValue = list.filter { value(it) != null }
            if (withValue.isEmpty()) return null
            val latest = value(withValue.last())!!
            var trend = ""
            var up = false
            if (withValue.size > 1) {
                val diff = latest.subtract(value(withValue[withValue.size - 2])!!)
                when {
                    diff.signum() > 0 -> {
                        up = true
                        trend = "↑ ${format(diff)}"
                    }
                    diff.signum() < 0 -> trend = "↓ ${format(diff.negate())}"
                    else -> trend = "sin cambio"
                }
            }
            return VitalSummary("$label$unit", format(latest), trend, up)
        }

        val result = mutableListOf<VitalSummary>()
        val lastIndex = list.indexOfLast { it.systolic != null && it.diastolic != null }
        if (lastIndex >= 0) {
            val last = list[lastIndex]
            val prev = list.filterIndexed { i, p -> i != lastIndex && p.systolic != null && p.diastolic != null }.lastOrNull()
            val up = prev != null && last.systolic!! > prev.systolic!!
            val down = prev != null && last.systolic!! < prev.systolic!!
            val trend = if (prev == null) "" else "${if (up) "↑ " else if (down) "↓ " else ""}antes ${prev.bloodPressureText}"
            result.add(VitalSummary("T/A (mmHg)", last.bloodPressureText, trend, up))
        }
        summary("Peso", { it.weight }, { NetDecimal.custom(it, 1, 1) }, " (kg)")?.let(result::add)
        summary("IMC", { it.bmi }, { NetDecimal.custom(it, 1, 1) }, "")?.let(result::add)
        summary("Glucosa", { it.glucose }, { NetDecimal.custom(it, 0, 0) }, " (mg/dL)")?.let(result::add)
        return result
    }

    /** Los textos de arriba de la gráfica con el último punto: "78.0 kg", "120/80 mmHg" (cada decimal tal cual), "95 mg/dL", "25.8". */
    fun latestTexts(points: List<VitalSignsRules.EvolutionPoint>): LatestVitalTexts {
        val latest = points.lastOrNull() ?: return LatestVitalTexts.NONE
        return LatestVitalTexts(
            latest.weight?.let { "${NetDecimal.fixed(it, 1)} kg" } ?: "--",
            if (latest.systolic != null && latest.diastolic != null) "${NetDecimal.text(latest.systolic)}/${NetDecimal.text(latest.diastolic)} mmHg" else "--",
            latest.glucose?.let { "${NetDecimal.fixed(it, 0)} mg/dL" } ?: "--",
            latest.bmi?.let { NetDecimal.fixed(it, 1) } ?: "--",
        )
    }
}
