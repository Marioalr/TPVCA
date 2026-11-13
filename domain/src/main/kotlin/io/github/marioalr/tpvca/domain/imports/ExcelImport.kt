package io.github.marioalr.tpvca.domain.imports

import io.github.marioalr.tpvca.domain.search.TextSearch
import io.github.marioalr.tpvca.domain.text.NetDecimal
import java.time.LocalDate
import java.util.Locale

/** Un renglón del catálogo: el nombre del medicamento y su clave (vacía si no trae). */
data class CatalogLine(val name: String, val code: String)

/** Un renglón de la hoja DOSIS PEDIA (`PediatricDoseRule`): el medicamento, el texto de la dosis, los ml por kg y las gotas por ml. */
data class PediatricLine(val medication: String, val doseText: String, val mlPerKg: Double?, val dropsPerMl: Int?)

/** Lo que trae el archivo, ya leído: las hojas que encontró o, de un CSV, solo el catálogo. */
class ExcelImportPlan(
    val isCsv: Boolean,
    /** `null`: el libro no trae hoja de medicamentos. */
    val catalog: List<CatalogLine>?,
    val posology: List<String>?,
    val generalMeasures: List<String>?,
    val pediatric: List<PediatricLine>?,
    /** La hoja RECETA: se lee después de guardar las plantillas, porque reconoce las posologías que ya conoce el consultorio. */
    val receta: XlsxSheet?,
    val crest: XlsxImage?,
    val header: XlsxImage?,
)

/** El médico del encabezado de la hoja RECETA (B1, B2, B3) y el teléfono y domicilio del pie. Lo vacío no cambia lo que ya está. */
data class RecetaDoctor(val name: String, val university: String, val license: String, val phone: String, val address: String)

/** Un medicamento de la receta del Excel: el nombre y su indicación (las líneas de posología que lo siguen). */
data class RecetaItem(val medicineName: String, val dosage: String)

/** El paciente de la hoja RECETA y su consulta de ese día. */
data class RecetaPatient(
    val fullName: String,
    val age: Int,
    val date: LocalDate,
    val allergies: String,
    val vitalSigns: String,
    val items: List<RecetaItem>,
    val generalMeasures: String,
    /** La fecha como venía, si no se pudo leer (entonces [date] es hoy y el resumen lo dice); `null` si se leyó. */
    val unreadDate: String? = null,
)

/** Lo que dice la hoja RECETA. */
data class Receta(val doctor: RecetaDoctor?, val patient: RecetaPatient?)

/**
 * Importar el Excel del consultorio (`DataImportService` de la PC, su formato de cinco hojas: MEDICAMENTOS, POSOLOGIA, M. GRALES, DOSIS PEDIA y RECETA) o un CSV con el catálogo: **solo leer**.
 * Lo que se escribe con esto lo hace el motor de escritura (`WriteEngine.importCatalog`), igual que la PC: lo que ya existe se actualiza y no se borra nada.
 */
object ExcelImport {
    /** Lo que empieza una línea de posología en la receta (`PosologyVerbs`). */
    val POSOLOGY_VERBS = listOf("tomar", "dar", "aplicar", "colocar", "administrar", "usar", "utilizar", "inhalar", "instilar", "diluir", "masticar", "disolver", "untar", "lavar", "nebulizar", "ofrecer", "cada", "via", "vía", "1 ", "una ", "un ")

    const val PENDING_DOSAGE = "Según indicación médica"

    /** El archivo por su extensión (`ImportExcelOrCsvAsync`): `.xlsx`, o `.csv` y `.txt`. */
    enum class Kind { Xlsx, Csv }

    fun kindOf(fileName: String): Kind? = when (fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "xlsx" -> Kind.Xlsx
        "csv", "txt" -> Kind.Csv
        else -> null
    }

    /** «Formato no soportado (.pdf). Seleccione…» */
    fun unsupported(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").let { if (it.isEmpty() || it == fileName) "" else ".$it" }.lowercase(Locale.ROOT)
        return "Formato no soportado ($ext). Seleccione un archivo Excel (.xlsx) o texto delimitado (.csv)."
    }

    // ------------------------------------------------------------------ libro de Excel

    fun findSheet(sheets: List<XlsxSheet>, name: String): XlsxSheet? = sheets.firstOrNull { TextSearch.normalize(it.name) == TextSearch.normalize(name) }

    fun plan(sheets: List<XlsxSheet>): ExcelImportPlan {
        // 1. MEDICAMENTOS, o la primera con «Medicamento» en el encabezado
        val medSheet = findSheet(sheets, "MEDICAMENTOS") ?: sheets.firstOrNull { s -> s.row(1).any { it.contains("Medicamento", ignoreCase = true) } }
        val posology = findSheet(sheets, "POSOLOGIA")?.let { singleColumnTexts(it, skipHeader = true) }
        val measures = (findSheet(sheets, "M. GRALES") ?: findSheet(sheets, "MEDIDAS GENERALES"))?.let { singleColumnTexts(it, skipHeader = false) }
        val pediatric = (findSheet(sheets, "DOSIS PEDIA") ?: findSheet(sheets, "DOSIS PEDIATRICA"))?.let(::pediatricLines)
        val receta = findSheet(sheets, "RECETA")
        val (crest, header) = receta?.let(::letterheadImages) ?: (null to null)
        return ExcelImportPlan(false, medSheet?.let(::catalogLines), posology, measures, pediatric, receta, crest, header)
    }

    /** Las columnas del medicamento y de la clave por su encabezado (de omisión A y B), y los renglones con nombre (`ImportMedicamentosSheetAsync`). */
    fun catalogLines(sheet: XlsxSheet): List<CatalogLine> {
        var colMed = 1
        var colClave = 2
        for ((i, raw) in sheet.row(1).withIndex()) {
            val h = raw.trim()
            if (h.contains("Medicamento", true) || h.contains("Producto", true) || h.contains("Nombre", true)) colMed = i + 1
            else if (h.contains("Clave", true) || h.contains("Código", true) || h.contains("Codigo", true)) colClave = i + 1
        }
        return (2..sheet.maxRow).mapNotNull { r ->
            val name = sheet.get(r, colMed).trim()
            if (name.isBlank()) null else CatalogLine(name, sheet.get(r, colClave).trim())
        }
    }

    /** Los textos de la columna A, una plantilla por celda. */
    fun singleColumnTexts(sheet: XlsxSheet, skipHeader: Boolean): List<String> =
        ((if (skipHeader) 2 else 1)..sheet.maxRow).map { normalizeTemplateText(sheet.get(it, 1)) }.filter { it.isNotBlank() }

    private val SPACES = Regex("\\s{2,}")
    private val BULLET = Regex("^•\\s*")

    /** Cada renglón sin tabuladores ni espacios dobles, las viñetas como «• » y sin renglones vacíos (`NormalizeTemplateText`). */
    fun normalizeTemplateText(text: String?): String {
        if (text.isNullOrBlank()) return ""
        return text.replace("\r\n", "\n").split('\n')
            .map { SPACES.replace(it.replace('\t', ' '), " ").trim() }
            .map { BULLET.replace(it, "• ") }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    /** El título de una plantilla: su primer renglón sin viñeta, de 60 caracteres como mucho (`TemplateTitle`). */
    fun templateTitle(text: String): String {
        val first = text.split('\n')[0].trimStart('•', ' ')
        return if (first.length <= 60) first else first.substring(0, 57).trimEnd() + "…"
    }

    private val DROPS = Regex("\\*\\s*(\\d+)")

    /** DOSIS PEDIA: MEDICAMENTO | DOSIS | PESO | ML | GOTAS, desde la fila 2 (`ImportPediatricDoseSheetAsync`). */
    fun pediatricLines(sheet: XlsxSheet): List<PediatricLine> = (2..sheet.maxRow).mapNotNull { r ->
        val medication = sheet.get(r, 1).trim()
        if (medication.isBlank()) return@mapNotNull null
        val dropsFormula = sheet.formula(r, 5).orEmpty()
        PediatricLine(medication, sheet.get(r, 2).trim(), parseMlPerKg(sheet.formula(r, 4), sheet.get(r, 3), sheet.get(r, 4)), DROPS.find(dropsFormula)?.groupValues?.get(1)?.toIntOrNull())
    }

    private val ML_FORMULA = Regex("^\\(?[A-Z]+\\d+\\*([\\d.]+)\\)?(?:/([\\d.]+))?$", RegexOption.IGNORE_CASE)

    /** "(C2*15)/32" → 0.46875 · "C5*0.5" → 0.5 · sin fórmula: ML / PESO si los dos existen (`ParseMlPerKg`). */
    fun parseMlPerKg(formula: String?, weightText: String, mlText: String): Double? {
        if (!formula.isNullOrBlank()) {
            val m = ML_FORMULA.matchEntire(formula.replace(" ", ""))
            val factor = m?.groupValues?.get(1)?.let(::number)
            if (m != null && factor != null) {
                val divisor = m.groupValues[2].takeIf { it.isNotEmpty() }?.let(::number) ?: 1.0
                if (divisor > 0) return factor / divisor
            }
        }
        val weight = number(weightText)
        val ml = number(mlText)
        return if (weight != null && weight > 0 && ml != null && ml > 0) ml / weight else null
    }

    /** `double.TryParse(…, NumberStyles.Any, InvariantCulture)` para lo que trae una celda: punto decimal, signo y exponente; las comas de miles se quitan. */
    internal fun number(text: String?): Double? {
        val t = text.orEmpty().trim().replace(",", "")
        if (t.isEmpty() || t.any { it.isLetter() && it != 'e' && it != 'E' }) return null
        return t.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    /**
     * El escudo (anclado en la columna A o B, en las primeras 4 filas) y la imagen del encabezado (la que empieza más a la derecha, de la columna G en adelante) de la hoja RECETA
     * (`FindLetterheadImages`). Los iconos del pie quedan fuera.
     */
    fun letterheadImages(sheet: XlsxSheet): Pair<XlsxImage?, XlsxImage?> {
        val top = sheet.images.filter { it.fromRow <= 4 && it.content.isNotEmpty() }
        val crest = top.filter { it.fromColumn <= 2 }.sortedWith(compareBy({ it.fromColumn }, { it.fromRow })).firstOrNull()
        val header = top.filter { it.fromColumn >= 7 && it !== crest }.sortedWith(compareByDescending<XlsxImage> { it.fromColumn }.thenBy { it.fromRow }).firstOrNull()
        return crest to header
    }

    // ------------------------------------------------------------------ hoja RECETA

    private val NON_DIGITS = Regex("[^\\d]")
    private val FIRST_NUMBER = Regex("\\d+")

    /** El médico, el paciente, sus signos, su receta y sus medidas generales (`ImportRecetaSheetAsync`). [knownPosologies]: las posologías que ya conoce el consultorio. */
    fun receta(sheet: XlsxSheet, knownPosologies: List<String>, today: LocalDate): Receta {
        // Pie: teléfono (10 a 12 dígitos) y domicilio, en las últimas filas
        var phone = ""
        var address = ""
        var r = sheet.maxRow
        while (r >= maxOf(1, sheet.maxRow - 4) && (phone.isEmpty() || address.isEmpty())) {
            for (cell in sheet.row(r).map { it.trim() }.filter { it.isNotEmpty() }) {
                val digits = cell.filter(Char::isDigit)
                if (phone.isEmpty() && digits.length in 10..12 && digits.length >= cell.length - 4) phone = digits
                else if (address.isEmpty() && cell.length > 12 && cell.any(Char::isLetter)) address = cell
            }
            r--
        }
        // Encabezado: B1 el nombre, B2 la universidad, B3 la cédula
        val candidate = sheet.get(1, "B").trim()
        val doctor = if (candidate.isNotBlank() && (candidate.startsWith("Dr", ignoreCase = true) || candidate.contains("Médic", ignoreCase = true))) {
            val license = sheet.get(3, "B").trim()
            val clean = NON_DIGITS.replace(license, "")
            RecetaDoctor(candidate, sheet.get(2, "B").trim(), if (license.isBlank()) "" else clean.ifBlank { license }, phone, address)
        } else {
            null
        }

        val patientRow = findRowStartingWith(sheet, "Paciente")
        if (patientRow == 0) return Receta(doctor, null)
        val name = valueAfterLabel(sheet, patientRow, "Paciente")
        if (name.isBlank()) return Receta(doctor, null)
        val ageText = valueAfterLabel(sheet, patientRow, "Edad")
        val dateRaw = valueAfterLabel(sheet, patientRow, "Fecha")
        val parsedDate = parseRecetaDate(dateRaw)
        val date = parsedDate ?: today
        val age = if (!ageText.contains("mes", ignoreCase = true)) FIRST_NUMBER.find(ageText)?.value?.toIntOrNull() ?: 0 else 0

        val vitalsRow = findRowStartingWith(sheet, "Alergias")
        // Vacío = no se anotó (no se supone «ninguna»)
        val allergies = if (vitalsRow > 0) valueAfterLabel(sheet, vitalsRow, "Alergias").trim() else ""
        val vitals = ArrayList<String>()
        if (vitalsRow > 0) {
            addVital(vitals, "TA", valueAfterLabel(sheet, vitalsRow, "T/A"), " mmHg")
            addVital(vitals, "Temp", valueAfterLabel(sheet, vitalsRow, "Temp"), "")
            addVital(vitals, "Peso", valueAfterLabel(sheet, vitalsRow, "Peso"), "")
            addVital(vitals, "Talla", valueAfterLabel(sheet, vitalsRow, "Talla"), "")
            var spo2 = valueAfterLabel(sheet, vitalsRow, "SpO2")
            number(spo2.trimEnd('%'))?.let { sat ->
                // Excel guarda 94 % como 0.94
                spo2 = NetDecimal.customDouble(if (sat <= 1) sat * 100 else sat, 0, 1) + "%"
            }
            addVital(vitals, "SpO2", spo2, "")
        }

        // Medicamentos (columna A) hasta el pie, y las medidas generales en el bloque de la derecha
        val firstRxRow = maxOf(patientRow, vitalsRow) + 1
        var footerRow = sheet.maxRow
        for (row in sheet.maxRow downTo firstRxRow + 1) {
            if (sheet.row(row).any { c -> c.isNotBlank() && ((phone.isNotEmpty() && c.contains(phone)) || c == address) }) footerRow = row
        }
        val known = knownPosologies.map(TextSearch::normalize).filter { it.isNotEmpty() }
        val items = ArrayList<Pair<String, String?>>()
        for (row in firstRxRow until footerRow) {
            val line = sheet.get(row, 1).trim()
            if (line.isBlank()) continue
            if (isPosologyLine(line, known) && items.isNotEmpty()) {
                val (medicine, dosage) = items.last()
                items[items.size - 1] = medicine to (if (dosage == null) line else "$dosage $line")
            } else {
                items += line to null
            }
        }
        var measures = ""
        var row = firstRxRow
        while (row < footerRow && measures.isEmpty()) {
            for (c in 2..sheet.maxColumn) {
                val cell = sheet.get(row, c).trim()
                if (!cell.startsWith("Medidas generales", ignoreCase = true)) continue
                // El texto puede estar en la misma celda tras los dos puntos o en las filas siguientes
                val inline = sheet.get(row, c).split(':', limit = 2).getOrNull(1)?.trim().orEmpty()
                val below = (row + 1 until footerRow).map { sheet.get(it, c).trim() }.filter { it.isNotEmpty() }
                measures = normalizeTemplateText((listOf(inline) + below).filter { it.isNotEmpty() }.joinToString("\n"))
                break
            }
            row++
        }
        return Receta(doctor, RecetaPatient(name, age, date, allergies, vitals.joinToString(" | "), items.map { RecetaItem(it.first, it.second ?: PENDING_DOSAGE) }, measures, dateRaw.takeIf { parsedDate == null }))
    }

    private val MONTHS = mapOf(
        "enero" to 1, "febrero" to 2, "marzo" to 3, "abril" to 4, "mayo" to 5, "junio" to 6, "julio" to 7, "agosto" to 8,
        "septiembre" to 9, "setiembre" to 9, "octubre" to 10, "noviembre" to 11, "diciembre" to 12,
        "ene" to 1, "feb" to 2, "mar" to 3, "abr" to 4, "may" to 5, "jun" to 6, "jul" to 7, "ago" to 8, "sep" to 9, "sept" to 9, "set" to 9, "oct" to 10, "nov" to 11, "dic" to 12,
    )

    private val WEEKDAY = Regex("^(lunes|martes|miercoles|jueves|viernes|sabado|domingo),?\\s+")
    private val TIME = Regex("[\\sT]+\\d{1,2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(\\s*[ap]\\.?\\s*m\\.?)?$")

    /** Día, mes (número o nombre, completo o abreviado) y año: «15/01/2026», «15-ene-2026», «15 de enero de 2026», «15 ene. 26». */
    private val DMY = Regex("^(\\d{1,2})\\s*(?:de\\s+|[/.\\-]\\s*|\\s+)([a-z]+|\\d{1,2})\\.?\\s*(?:del?\\s+|[/.\\-]\\s*|\\s+|,\\s*)(\\d{4}|\\d{2})$")
    private val YMD = Regex("^(\\d{4})[/.\\-](\\d{1,2})[/.\\-](\\d{1,2})$")

    /**
     * La fecha de la receta: un número de serie de Excel (entre 30000 y 80000) o una fecha escrita como la lee `DateTime.TryParse` con la cultura es-MX de la PC: día, mes y año con número o
     * con el nombre del mes («15 de enero de 2026», «15/ene/2026»), con o sin el día de la semana y la hora; año de dos dígitos hasta 49 → 20xx. `null` si no se lee.
     */
    fun parseRecetaDate(raw: String): LocalDate? {
        val serial = number(raw)
        if (serial != null) return if (serial > 30000 && serial < 80000) LocalDate.of(1899, 12, 30).plusDays(serial.toLong()) else null
        var text = TextSearch.normalize(raw)
        if (text.isEmpty()) return null
        text = WEEKDAY.replace(text, "")
        text = TIME.replace(text, "").trim()
        return try {
            YMD.matchEntire(text)?.let { m -> return LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }
            val m = DMY.matchEntire(text) ?: return null
            val month = m.groupValues[2].toIntOrNull() ?: MONTHS[m.groupValues[2]] ?: return null
            var year = m.groupValues[3].toInt()
            if (m.groupValues[3].length == 2) year += if (year <= 49) 2000 else 1900
            LocalDate.of(year, month, m.groupValues[1].toInt())
        } catch (_: java.time.DateTimeException) {
            null
        }
    }

    /** La fecha que se usa: la de la receta, o hoy si no se pudo leer (y [RecetaPatient.dateText] dice qué decía). */
    fun recetaDate(raw: String, today: LocalDate): LocalDate = parseRecetaDate(raw) ?: today

    fun isPosologyLine(line: String, knownKeys: List<String>): Boolean {
        val normalized = TextSearch.normalize(line)
        if (POSOLOGY_VERBS.any { normalized.startsWith(it) }) return true
        return knownKeys.any { normalized.startsWith(it.substring(0, minOf(it.length, 18))) }
    }

    fun findRowStartingWith(sheet: XlsxSheet, label: String): Int = (1..sheet.maxRow).firstOrNull { sheet.get(it, 1).trim().startsWith(label, ignoreCase = true) } ?: 0

    /** El valor a la derecha de una etiqueta («Edad:» → «4 años»); vacío si la siguiente celda es otra etiqueta (`ValueAfterLabel`). */
    fun valueAfterLabel(sheet: XlsxSheet, row: Int, label: String): String {
        for (c in 1..sheet.maxColumn) {
            val cell = sheet.get(row, c).trim()
            if (!cell.startsWith(label, ignoreCase = true)) continue
            val inline = cell.split(':', limit = 2).getOrNull(1)?.trim()
            if (!inline.isNullOrBlank()) return inline
            for (next in c + 1..sheet.maxColumn) {
                val value = sheet.get(row, next).trim()
                if (value.isEmpty()) continue
                return if (value.endsWith(':')) "" else value
            }
            return ""
        }
        return ""
    }

    private fun addVital(vitals: MutableList<String>, label: String, value: String, unit: String) {
        if (value.isBlank()) return
        vitals += "$label: $value${if (value.any(Char::isLetter) || value.contains('%')) "" else unit}"
    }

    // ------------------------------------------------------------------ catálogo: nombre, genérico y presentación

    private val DOSE = Regex("^(.*?)\\s+(\\d+(?:\\.\\d+)?\\s*(?:mg|g|mcg|ml|%|UI)(?:/\\d+(?:\\.\\d+)?\\s*(?:mg|g|mcg|ml))?.*)$", RegexOption.IGNORE_CASE)
    private val FORM = Regex("^(.*?)\\s+(tabletas|c[aá]psulas|suspensi[oó]n|soluci[oó]n|crema|ung[uü]ento|jarabe|gotas|spray|[aá]mpula|gel|ovulos|inyectable).*$", RegexOption.IGNORE_CASE)

    /** El genérico y la presentación de un nombre: por su dosis («500 mg…») o su forma («tabletas…») (`ParseDrugDetails`). */
    fun drugDetails(fullName: String): Pair<String?, String?> {
        if (fullName.isBlank()) return null to null
        DOSE.matchEntire(fullName)?.let { return it.groupValues[1].trim() to it.groupValues[2].trim() }
        FORM.matchEntire(fullName)?.let {
            val generic = it.groupValues[1].trim()
            return generic to fullName.substring(generic.length).trim()
        }
        return fullName.trim() to null
    }

    // ------------------------------------------------------------------ CSV

    /** Un CSV o texto delimitado: el separador por su primer renglón (`;` si no hay `,`, o tabulador) y las columnas por su encabezado (de omisión la 1 y la 2) (`ImportCsvInternalAsync`). */
    fun csvPlan(text: String): ExcelImportPlan? {
        val lines = text.removePrefix("\uFEFF").split("\r\n", "\n", "\r").let { if (it.lastOrNull()?.isEmpty() == true) it.dropLast(1) else it }
        if (lines.isEmpty() || (lines.size == 1 && lines[0].isEmpty())) return null
        val delimiter = when {
            lines[0].contains(';') && !lines[0].contains(',') -> ';'
            lines[0].contains('\t') -> '\t'
            else -> ','
        }
        val header = splitCsvLine(lines[0], delimiter)
        var colMed = 0
        var colClave = 1
        for ((i, h) in header.withIndex()) {
            if (h.contains("Medicamento", true) || h.contains("Producto", true) || h.contains("Nombre", true)) colMed = i
            else if (h.contains("Clave", true) || h.contains("Código", true) || h.contains("Codigo", true)) colClave = i
        }
        val rows = lines.drop(1).filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = splitCsvLine(line, delimiter)
            val name = parts.getOrNull(colMed).orEmpty()
            if (name.isBlank()) null else CatalogLine(name, parts.getOrNull(colClave).orEmpty())
        }
        return ExcelImportPlan(true, rows, null, null, null, null, null, null)
    }

    /** Una línea respetando las comillas ("Paracetamol, gotas";PAR-1), cada campo sin espacios a los lados. */
    fun splitCsvLine(line: String, delimiter: Char): List<String> {
        val fields = ArrayList<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                    current.append('"')
                    i++
                } else {
                    inQuotes = !inQuotes
                }
                c == delimiter && !inQuotes -> {
                    fields += current.toString().trim()
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        fields += current.toString().trim()
        return fields
    }

    // ------------------------------------------------------------------ resultado

    /** El resumen que se confirma antes de importar: un renglón por cosa que cambia. [unreadDate]: la fecha de la receta no se leyó. */
    data class Preview(val lines: List<String>, val unreadDate: Boolean)

    /** Lo que se dice siempre antes de importar en la tablet. */
    const val NO_BACKUP_WARNING =
        "La tablet no hace un respaldo antes de importar: lo que se reemplace no se puede recuperar desde aquí. Si necesita poder volver atrás, importe el archivo en la PC, que guarda un respaldo antes."

    /** «A, B y C», y si son más de [max], «A, B, C… y 4 más». */
    fun listed(items: List<String>, max: Int = 10): String {
        if (items.size <= max) return joined(items)
        return items.take(max).joinToString(", ") + " y ${items.size - max} más"
    }

    fun joined(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " y " + items.last()
    }

    /** Lo que se importó (`DataImportResult`). */
    data class Result(
        val productsImported: Int = 0,
        val productsUpdated: Int = 0,
        val patientsImported: Int = 0,
        val doctorName: String? = null,
        val templatesImported: Int = 0,
        val pediatricRulesImported: Int = 0,
        val letterheadImagesImported: Int = 0,
        val isCsv: Boolean = false,
    ) {
        val doctorProfileUpdated: Boolean get() = doctorName != null

        /** El mensaje de la PC, renglón por renglón. */
        val message: String
            get() = if (isCsv) {
                "✓ CSV importado: $productsImported medicamentos nuevos, $productsUpdated actualizados."
            } else {
                buildString {
                    append("✓ Catálogo procesado: $productsImported medicamentos nuevos, $productsUpdated actualizados.")
                    if (templatesImported > 0) append("\n✓ $templatesImported plantillas de posología / medidas generales.")
                    if (pediatricRulesImported > 0) append("\n✓ $pediatricRulesImported reglas de dosis pediátrica por peso.")
                    if (doctorProfileUpdated) append("\n✓ Perfil médico actualizado ($doctorName).")
                    if (letterheadImagesImported > 0) append("\n✓ Imágenes del membrete de la receta tomadas del Excel.")
                    if (patientsImported > 0) append("\n✓ $patientsImported expediente clínico y consulta importados.")
                }
            }

        /** Los renglones de la hoja al terminar (`ImportResultLines`), sin el respaldo (la tablet no hace respaldos). */
        val lines: List<String>
            get() = buildList {
                if (productsImported > 0) add("$productsImported medicamento(s) nuevo(s)")
                if (productsUpdated > 0) add("$productsUpdated medicamento(s) actualizado(s)")
                if (patientsImported > 0) add("$patientsImported expediente(s)")
                if (templatesImported > 0) add("$templatesImported plantilla(s) de receta")
                if (pediatricRulesImported > 0) add("$pediatricRulesImported dosis pediátrica(s)")
                if (doctorProfileUpdated) add("Perfil del médico: $doctorName")
            }
    }
}
