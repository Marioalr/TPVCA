package io.github.marioalr.tpvca.domain.consultation

import io.github.marioalr.tpvca.domain.model.LabStudy
import io.github.marioalr.tpvca.domain.search.TextSearch

/** Un estudio del catálogo (`LabCatalogStudy`): nombre, área y la abreviatura con que lo escribe el médico (BH, QS, EGO…). */
data class LabCatalogStudy(
    val name: String,
    val area: String,
    val abbreviation: String = "",
    /** `true` si no es del catálogo: lo escribió el consultorio en otra consulta. */
    val isCustom: Boolean = false,
    /** El texto corto del botón de los frecuentes ("BH", "QS 6"); vacío si no tiene. */
    val chip: String = "",
) {
    /** "Biometría hemática completa (BH)", como se ve en las sugerencias. */
    val display: String get() = if (abbreviation.isNotEmpty()) "$name ($abbreviation)" else name

    val chipText: String get() = chip.ifEmpty { name }
}

/** Un grupo de estudios que se piden juntos (`LabPanel`): chequeo general, control de diabetes… */
data class LabPanel(val name: String, val studies: List<String>) {
    val summary: String get() = studies.joinToString(", ")
}

/** Una indicación de un toque para el paciente (`LabIndicationPreset`): el texto del botón y lo que agrega. */
data class LabIndicationPreset(val label: String, val text: String)

/**
 * Los estudios de laboratorio que se piden en un consultorio de primer contacto, agrupados por área (`Services/LabCatalog.cs`, desde v1.25.0 de la PC). Es la lista de sugerencias de la
 * orden; lo que el médico escriba fuera de ella también se guarda y vuelve a sugerirse en las consultas siguientes. Sin vector: se comprueba con `LabCatalogTest`, con los casos de
 * `LabOrderTests.cs`.
 */
object LabCatalog {
    const val HEMATOLOGY = "Hematología"
    const val CHEMISTRY = "Química clínica"
    const val URINALYSIS = "Urianálisis"
    const val STOOL = "Coprología y parasitología"
    const val IMMUNOLOGY = "Inmunología y serología"
    const val HORMONES = "Hormonas"
    const val MICROBIOLOGY = "Microbiología"
    const val OTHER_AREA = "Otros"

    /** Las áreas en el orden en que salen en la hoja. */
    val AREAS: List<String> = listOf(HEMATOLOGY, CHEMISTRY, URINALYSIS, STOOL, IMMUNOLOGY, HORMONES, MICROBIOLOGY, OTHER_AREA)

    val STUDIES: List<LabCatalogStudy> = listOf(
        LabCatalogStudy("Biometría hemática completa", HEMATOLOGY, "BH"),
        LabCatalogStudy("Velocidad de sedimentación globular", HEMATOLOGY, "VSG"),
        LabCatalogStudy("Tiempos de coagulación (TP, TTPa, INR)", HEMATOLOGY, "TP"),
        LabCatalogStudy("Grupo sanguíneo y factor Rh", HEMATOLOGY),
        LabCatalogStudy("Reticulocitos", HEMATOLOGY),
        LabCatalogStudy("Frotis de sangre periférica", HEMATOLOGY),

        LabCatalogStudy("Glucosa en ayuno", CHEMISTRY),
        LabCatalogStudy("Química sanguínea de 3 elementos", CHEMISTRY, "QS 3"),
        LabCatalogStudy("Química sanguínea de 6 elementos", CHEMISTRY, "QS 6"),
        LabCatalogStudy("Química sanguínea de 27 elementos", CHEMISTRY, "QS 27"),
        LabCatalogStudy("Hemoglobina glucosilada", CHEMISTRY, "HbA1c"),
        LabCatalogStudy("Curva de tolerancia a la glucosa", CHEMISTRY, "CTOG"),
        LabCatalogStudy("Perfil de lípidos", CHEMISTRY),
        LabCatalogStudy("Pruebas de función hepática", CHEMISTRY, "PFH"),
        LabCatalogStudy("Electrolitos séricos (Na, K, Cl)", CHEMISTRY, "ES"),
        LabCatalogStudy("Creatinina sérica", CHEMISTRY),
        LabCatalogStudy("Ácido úrico", CHEMISTRY),
        LabCatalogStudy("Calcio, fósforo y magnesio", CHEMISTRY),
        LabCatalogStudy("Amilasa y lipasa", CHEMISTRY),
        LabCatalogStudy("Perfil de hierro (hierro, ferritina y transferrina)", CHEMISTRY),
        LabCatalogStudy("Vitamina D (25-hidroxi)", CHEMISTRY),
        LabCatalogStudy("Vitamina B12", CHEMISTRY),
        LabCatalogStudy("Microalbuminuria", CHEMISTRY),

        LabCatalogStudy("Examen general de orina", URINALYSIS, "EGO"),
        LabCatalogStudy("Urocultivo con antibiograma", URINALYSIS),
        LabCatalogStudy("Depuración de creatinina en orina de 24 horas", URINALYSIS),
        LabCatalogStudy("Proteínas en orina de 24 horas", URINALYSIS),

        LabCatalogStudy("Coproparasitoscópico seriado (3 muestras)", STOOL, "CPS"),
        LabCatalogStudy("Coprológico", STOOL),
        LabCatalogStudy("Sangre oculta en heces", STOOL),
        LabCatalogStudy("Amiba en fresco", STOOL),
        LabCatalogStudy("Coprocultivo", STOOL),
        LabCatalogStudy("Antígeno de Helicobacter pylori en heces", STOOL),
        LabCatalogStudy("Rotavirus en heces", STOOL),

        LabCatalogStudy("Proteína C reactiva", IMMUNOLOGY, "PCR"),
        LabCatalogStudy("Factor reumatoide", IMMUNOLOGY, "FR"),
        LabCatalogStudy("Antiestreptolisinas", IMMUNOLOGY, "ASO"),
        LabCatalogStudy("Reacciones febriles", IMMUNOLOGY),
        LabCatalogStudy("Prueba de embarazo en sangre (fracción beta de HGC)", IMMUNOLOGY, "HGC"),
        LabCatalogStudy("Prueba de embarazo en orina", IMMUNOLOGY),
        LabCatalogStudy("VDRL", IMMUNOLOGY),
        LabCatalogStudy("VIH (anticuerpos)", IMMUNOLOGY, "VIH"),
        LabCatalogStudy("Antígeno de superficie de hepatitis B", IMMUNOLOGY, "HBsAg"),
        LabCatalogStudy("Anticuerpos contra hepatitis C", IMMUNOLOGY, "VHC"),
        LabCatalogStudy("Antígeno prostático específico total y libre", IMMUNOLOGY, "APE"),
        LabCatalogStudy("Dengue (antígeno NS1 e IgM/IgG)", IMMUNOLOGY),
        LabCatalogStudy("Influenza A y B (antígeno)", IMMUNOLOGY),
        LabCatalogStudy("SARS-CoV-2 (antígeno)", IMMUNOLOGY),

        LabCatalogStudy("Perfil tiroideo (TSH, T3 y T4)", HORMONES),
        LabCatalogStudy("Hormona estimulante de la tiroides", HORMONES, "TSH"),
        LabCatalogStudy("Insulina basal", HORMONES),
        LabCatalogStudy("Perfil hormonal femenino (FSH, LH, estradiol, progesterona y prolactina)", HORMONES),
        LabCatalogStudy("Prolactina", HORMONES),
        LabCatalogStudy("Testosterona total", HORMONES),
        LabCatalogStudy("Cortisol matutino", HORMONES),

        LabCatalogStudy("Exudado faríngeo con cultivo", MICROBIOLOGY),
        LabCatalogStudy("Exudado vaginal con cultivo", MICROBIOLOGY),
        LabCatalogStudy("Cultivo de secreción con antibiograma", MICROBIOLOGY),
        LabCatalogStudy("Hemocultivo", MICROBIOLOGY),
    )

    /** Los que más se piden: botones de un toque en la sección de laboratorio. */
    val FREQUENT: List<LabCatalogStudy> = listOf(
        "BH" to "Biometría hemática completa",
        "QS 6" to "Química sanguínea de 6 elementos",
        "EGO" to "Examen general de orina",
        "Perfil de lípidos" to "Perfil de lípidos",
        "HbA1c" to "Hemoglobina glucosilada",
        "PFH" to "Pruebas de función hepática",
        "Perfil tiroideo" to "Perfil tiroideo (TSH, T3 y T4)",
        "Coproparasitoscópico" to "Coproparasitoscópico seriado (3 muestras)",
        "PCR" to "Proteína C reactiva",
        "Embarazo en orina" to "Prueba de embarazo en orina",
    ).map { (chip, name) -> find(name)!!.copy(chip = chip) }

    val PANELS: List<LabPanel> = listOf(
        LabPanel("Chequeo general", listOf("Biometría hemática completa", "Química sanguínea de 6 elementos", "Perfil de lípidos", "Examen general de orina")),
        LabPanel("Control de diabetes", listOf("Glucosa en ayuno", "Hemoglobina glucosilada", "Perfil de lípidos", "Química sanguínea de 3 elementos", "Microalbuminuria", "Examen general de orina")),
        LabPanel(
            "Control prenatal",
            listOf("Biometría hemática completa", "Grupo sanguíneo y factor Rh", "Glucosa en ayuno", "Examen general de orina", "Urocultivo con antibiograma", "VDRL", "VIH (anticuerpos)"),
        ),
    )

    /** Indicaciones de un toque para el paciente. */
    val INDICATION_PRESETS: List<LabIndicationPreset> = listOf(
        LabIndicationPreset("Ayuno", "Ayuno de 8 a 12 horas (puede tomar agua)."),
        LabIndicationPreset("Primera orina", "Orina: la primera de la mañana, del chorro medio, en frasco estéril."),
        LabIndicationPreset("Antes del antibiótico", "Tomar las muestras antes de iniciar el antibiótico."),
        LabIndicationPreset("Traer resultados", "Traer los resultados a su próxima consulta."),
    )

    /** El estudio del catálogo con ese nombre o esa abreviatura (sin importar acentos ni mayúsculas); `null` si no está. */
    fun find(nameOrAbbreviation: String?): LabCatalogStudy? {
        val key = TextSearch.normalize(nameOrAbbreviation)
        if (key.isEmpty()) return null
        return STUDIES.firstOrNull { TextSearch.normalize(it.name) == key }
            ?: STUDIES.firstOrNull { it.abbreviation.isNotEmpty() && TextSearch.normalize(it.abbreviation) == key }
    }

    /** El área de un estudio: la del catálogo, u "Otros" si lo escribió el médico. */
    fun areaOf(name: String?): String = find(name)?.area ?: OTHER_AREA

    /** Lugar del área en la hoja (las que no conoce, al final). */
    fun areaOrder(area: String?): Int {
        val index = AREAS.indexOfFirst { it.equals(area, ignoreCase = true) }
        return if (index < 0) AREAS.size else index
    }

    /**
     * Sugerencias para lo escrito: el catálogo y los estudios que el consultorio ya pidió ([custom]). Cada palabra escrita debe aparecer en el nombre o la abreviatura; primero la
     * abreviatura exacta, luego lo que empieza igual, luego una palabra que empieza igual y al final lo que la contiene. Hasta [max].
     */
    fun search(query: String?, custom: Iterable<LabCatalogStudy>? = null, max: Int = 8): List<LabCatalogStudy> {
        val q = TextSearch.normalize(query)
        if (q.isEmpty()) return emptyList()
        val words = q.split(' ').filter { it.isNotEmpty() }
        val candidates = ArrayList(STUDIES)
        if (custom != null) {
            val known = STUDIES.map { TextSearch.normalize(it.name) }.toHashSet()
            for (c in custom) if (known.add(TextSearch.normalize(c.name))) candidates += c
        }
        return candidates
            .mapNotNull { study ->
                val name = TextSearch.normalize(study.name)
                val abbreviation = TextSearch.normalize(study.abbreviation)
                val text = "$name $abbreviation"
                if (!words.all { text.contains(it) }) return@mapNotNull null
                val rank = when {
                    abbreviation.isNotEmpty() && abbreviation == q -> 0
                    name.startsWith(q) -> 1
                    abbreviation.isNotEmpty() && abbreviation.startsWith(q) -> 2
                    " $name".contains(" " + words[0]) -> 3
                    else -> 4
                }
                study to rank
            }
            .sortedWith(compareBy({ it.second }, { it.first.name.length }))
            .take(max)
            .map { it.first }
    }

    /** El estudio que entra a la orden: el del catálogo (con su nombre completo y su área) o lo escrito, en "Otros". */
    fun toStudy(name: String): LabStudy {
        val known = find(name)
        return if (known != null) LabStudy(known.name, known.area) else LabStudy(name.trim(), OTHER_AREA)
    }

    /** Los estudios que el consultorio ya pidió fuera del catálogo (`GetCustomLabStudiesAsync`): los más pedidos primero, luego por nombre, sin repetir. [ordered]: los de todas las consultas con orden. */
    fun customStudies(ordered: Iterable<LabStudy>): List<LabCatalogStudy> =
        ordered.filter { it.name.isNotBlank() && find(it.name) == null }
            .groupBy { TextSearch.normalize(it.name) }
            .values
            .sortedWith(compareBy<List<LabStudy>>({ -it.size }, { it.first().name.lowercase() }))
            .map { group -> LabCatalogStudy(group.first().name, group.first().area.ifBlank { OTHER_AREA }, isCustom = true) }
}

/** La orden de laboratorio de la consulta en curso, como valores (`ConsultationLabOrder`): agregar sin repetir, quitar y las indicaciones en su propio renglón. */
object LabOrderRules {
    /** Agrega el estudio si no está ya (mismo nombre sin importar acentos ni mayúsculas); `null` si ya estaba o no tiene nombre. */
    fun add(studies: List<LabStudy>, study: LabStudy): List<LabStudy>? {
        if (study.name.isBlank()) return null
        val key = TextSearch.normalize(study.name)
        if (studies.any { TextSearch.normalize(it.name) == key }) return null
        return studies + LabStudy(study.name.trim(), study.area.trim().ifEmpty { LabCatalog.OTHER_AREA })
    }

    /** Agrega una indicación en su propio renglón; si ya está escrita, no la repite. */
    fun appendIndication(current: String, text: String?): String {
        val added = text?.trim().orEmpty()
        if (added.isEmpty() || current.contains(added, ignoreCase = true)) return current
        return if (current.isBlank()) added else current.trimEnd() + "\n" + added
    }

    /** "Biometría hemática completa, Examen general de orina" (el encabezado de la sección cerrada y la tarjeta del historial). */
    fun summary(studies: List<LabStudy>): String = if (studies.isEmpty()) "Sin estudios (opcional)" else studies.joinToString(", ") { it.name }
}
