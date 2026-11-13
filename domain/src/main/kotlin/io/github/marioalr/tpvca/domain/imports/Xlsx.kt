package io.github.marioalr.tpvca.domain.imports

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Una hoja de un .xlsx (`XlsxSheet` de la PC): celdas por número real de fila (1..n) y columna (A = 1), con sus fórmulas. Las filas vacías que Excel omite en el XML no desplazan a las demás.
 */
class XlsxSheet(val name: String) {
    private val values = HashMap<Pair<Int, Int>, String>()
    private val formulas = HashMap<Pair<Int, Int>, String>()

    var maxRow: Int = 0
        private set
    var maxColumn: Int = 0
        private set

    /** Imágenes colocadas sobre la hoja (su dibujo), en el orden del archivo. */
    val images = ArrayList<XlsxImage>()

    internal fun set(row: Int, col: Int, value: String, formula: String?) {
        // Los límites de Excel: una celda más allá es un archivo dañado (y recorrerlo no terminaría)
        if (row !in 1..Xlsx.MAX_ROWS || col !in 1..Xlsx.MAX_COLUMNS) throw XlsxException(Xlsx.OUT_OF_BOUNDS)
        if (value.isNotEmpty()) values[row to col] = value
        if (!formula.isNullOrEmpty()) formulas[row to col] = formula
        maxRow = maxOf(maxRow, row)
        maxColumn = maxOf(maxColumn, col)
    }

    fun get(row: Int, col: Int): String = values[row to col].orEmpty()

    fun get(row: Int, column: String): String = get(row, Xlsx.columnIndex(column))

    fun formula(row: Int, col: Int): String? = formulas[row to col]

    /** Los valores de la fila desde la columna A hasta la última usada en la hoja. */
    fun row(row: Int): List<String> = (1..maxColumn).map { get(row, it) }
}

/**
 * Una imagen sobre una hoja (`XlsxImage`): la celda donde empieza y donde termina (fila 1..n, columna A = 1), la parte de `xl/media` y el recorte que Excel le aplica al mostrarla (fracciones de 0 a 1).
 */
class XlsxImage(
    val fromRow: Int,
    val fromColumn: Int,
    val toRow: Int,
    val toColumn: Int,
    val mediaPath: String,
    val content: ByteArray,
    val cropLeft: Double = 0.0,
    val cropTop: Double = 0.0,
    val cropRight: Double = 0.0,
    val cropBottom: Double = 0.0,
) {
    val isCropped: Boolean get() = cropLeft > 0 || cropTop > 0 || cropRight > 0 || cropBottom > 0
}

/** El libro no se pudo leer: el texto es para el médico. */
class XlsxException(message: String) : Exception(message)

/**
 * Lector OpenXML mínimo (`XlsxReader` de la PC, sin dependencias): las celdas con su valor y su fórmula, las cadenas compartidas y las imágenes de cada hoja. Kotlin puro: zip y DOM de Java, sin
 * resolver entidades ni DTD (un libro nunca las trae).
 */
object Xlsx {
    private const val MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val PACKAGE_REL = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val SHEET_DRAWING = "http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing"
    private const val DRAWING_MAIN = "http://schemas.openxmlformats.org/drawingml/2006/main"

    /** Lo más que se descomprime de un libro: un catálogo del consultorio pesa unos cuantos MB; esto ataja un archivo dañado o malicioso sin agotar la memoria de la tablet. */
    const val MAX_UNZIPPED_BYTES = 30L * 1024 * 1024

    /** Lo más que se lee del archivo elegido. */
    const val MAX_FILE_BYTES = 25L * 1024 * 1024

    /** Los límites de una hoja de Excel: 1,048,576 filas y 16,384 columnas (XFD). */
    const val MAX_ROWS = 1_048_576
    const val MAX_COLUMNS = 16_384

    const val OUT_OF_BOUNDS = "El libro tiene una celda fuera de los límites de Excel; puede estar dañado."
    const val TOO_BIG = "El archivo es demasiado grande para importarlo en la tablet (hasta 25 MB). Quite las hojas o imágenes que no hacen falta, o impórtelo en la PC."

    private val COLUMN_LETTERS = Regex("^[A-Za-z]+")

    fun read(bytes: ByteArray): List<XlsxSheet> {
        val zip = unzip(bytes)
        val shared = sharedStrings(zip)
        val relationships = workbookRelationships(zip)
        val workbook = zip["xl/workbook.xml"]?.let(::parse) ?: throw XlsxException("El archivo no es un libro de Excel válido (falta xl/workbook.xml).")
        val sheets = ArrayList<XlsxSheet>()
        var position = 0
        for (sheetElement in elements(workbook.documentElement, MAIN, "sheet", deep = true)) {
            position++
            val name = sheetElement.getAttribute("name").ifEmpty { "Hoja$position" }
            val relId = sheetElement.getAttributeNS(REL, "id").ifEmpty { null }
            val path = relId?.let(relationships::get) ?: "xl/worksheets/sheet$position.xml"
            val content = zip[path] ?: continue
            val sheet = XlsxSheet(name.trim())
            val doc = parse(content)
            var nextRow = 0
            for (rowElement in elements(doc.documentElement, MAIN, "row", deep = true)) {
                val rowNumber = rowElement.getAttribute("r").let { if (it.isEmpty()) nextRow + 1 else it.toIntOrNull() ?: throw XlsxException(OUT_OF_BOUNDS) }
                nextRow = rowNumber
                var nextCol = 0
                for (cell in elements(rowElement, MAIN, "c")) {
                    val reference = cell.getAttribute("r")
                    val col = if (reference.isNotEmpty()) columnIndex(COLUMN_LETTERS.find(reference)?.value.orEmpty()) else nextCol + 1
                    nextCol = col
                    val formula = elements(cell, MAIN, "f").firstOrNull()?.textContent
                    sheet.set(rowNumber, col, cellValue(cell, shared), formula)
                }
            }
            readImages(zip, path, doc, sheet.images)
            sheets += sheet
        }
        return sheets
    }

    /** "A" → 1, "Z" → 26, "AA" → 27. Más allá de XFD regresa [MAX_COLUMNS] + 1 (sin desbordarse). */
    fun columnIndex(letters: String): Int {
        var index = 0
        for (c in letters.uppercase()) {
            if (c !in 'A'..'Z') continue
            index = index * 26 + (c - 'A' + 1)
            if (index > MAX_COLUMNS) return MAX_COLUMNS + 1
        }
        return index
    }

    /** Una parte con DTD o entidades no es de un libro de Excel: se rechaza antes de analizarla (el analizador de Android no siempre deja apagarlas). */
    private fun rejectDoctype(content: ByteArray) {
        val text = if (content.size >= 2 && ((content[0] == 0xFE.toByte() && content[1] == 0xFF.toByte()) || (content[0] == 0xFF.toByte() && content[1] == 0xFE.toByte()))) {
            String(content, Charsets.UTF_16)
        } else {
            String(content, Charsets.ISO_8859_1)
        }
        if (text.contains("<!DOCTYPE", ignoreCase = true) || text.contains("<!ENTITY", ignoreCase = true)) throw XlsxException("El archivo trae definiciones que un libro de Excel no usa; no se importa.")
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val entries = HashMap<String, ByteArray>()
        var total = 0L
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_UNZIPPED_BYTES) throw XlsxException(TOO_BIG)
                        out.write(buffer, 0, n)
                    }
                    entries[entry.name.trimStart('/')] = out.toByteArray()
                }
            }
        } catch (e: IOException) {
            throw XlsxException("El archivo no es un libro de Excel válido.")
        }
        if (entries.isEmpty()) throw XlsxException("El archivo no es un libro de Excel válido.")
        return entries
    }

    private fun parse(content: ByteArray): Document {
        rejectDoctype(content)
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.isExpandEntityReferences = false
        // Sin DTD ni entidades externas; un analizador que no conoce la opción tampoco las resuelve
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        return try {
            factory.newDocumentBuilder().parse(ByteArrayInputStream(content))
        } catch (e: Exception) {
            throw XlsxException("El archivo no es un libro de Excel válido.")
        }
    }

    /** Los hijos (o todos los descendientes, con [deep]) con ese nombre y espacio de nombres. */
    private fun elements(parent: Element?, namespace: String, local: String, deep: Boolean = false): List<Element> {
        if (parent == null) return emptyList()
        if (deep) {
            val list = parent.getElementsByTagNameNS(namespace, local)
            return (0 until list.length).map { list.item(it) as Element }
        }
        val result = ArrayList<Element>()
        var child = parent.firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE && child.namespaceURI == namespace && child.localName == local) result += child as Element
            child = child.nextSibling
        }
        return result
    }

    private fun cellValue(cell: Element, shared: List<String>): String {
        val type = cell.getAttribute("t")
        val raw = elements(cell, MAIN, "v").firstOrNull()?.textContent.orEmpty()
        return when (type) {
            "s" -> raw.toIntOrNull()?.takeIf { it in shared.indices }?.let(shared::get).orEmpty()
            "inlineStr" -> elements(cell, MAIN, "t", deep = true).joinToString("") { it.textContent }
            "b" -> if (raw == "1") "VERDADERO" else "FALSO"
            else -> raw
        }
    }

    private fun sharedStrings(zip: Map<String, ByteArray>): List<String> {
        val doc = zip["xl/sharedStrings.xml"]?.let(::parse) ?: return emptyList()
        // Texto simple <t> o enriquecido <r><t>; se omiten las guías fonéticas <rPh>
        return elements(doc.documentElement, MAIN, "si").map { si ->
            (elements(si, MAIN, "t").map { it.textContent } + elements(si, MAIN, "r").flatMap { r -> elements(r, MAIN, "t") }.map { it.textContent }).joinToString("")
        }
    }

    private fun workbookRelationships(zip: Map<String, ByteArray>): Map<String, String> {
        val doc = zip["xl/_rels/workbook.xml.rels"]?.let(::parse) ?: return emptyMap()
        return elements(doc.documentElement, PACKAGE_REL, "Relationship", deep = true).mapNotNull { rel ->
            val id = rel.getAttribute("Id")
            val target = rel.getAttribute("Target")
            if (id.isEmpty() || target.isEmpty()) null else id to (if (target.startsWith("/")) target.trimStart('/') else "xl/$target")
        }.toMap()
    }

    /** Las relaciones de una parte (su `_rels/X.rels`), con los destinos como rutas del zip; las externas no cuentan. */
    private fun partRelationships(zip: Map<String, ByteArray>, partPath: String): Map<String, String> {
        val slash = partPath.lastIndexOf('/')
        val folder = if (slash >= 0) partPath.substring(0, slash) else ""
        val relsPath = (if (folder.isNotEmpty()) "$folder/" else "") + "_rels/" + partPath.substring(slash + 1) + ".rels"
        val doc = zip[relsPath]?.let(::parse) ?: return emptyMap()
        return elements(doc.documentElement, PACKAGE_REL, "Relationship", deep = true).mapNotNull { rel ->
            val id = rel.getAttribute("Id")
            val target = rel.getAttribute("Target")
            if (id.isEmpty() || target.isEmpty() || rel.getAttribute("TargetMode") == "External") null else id to resolve(folder, target)
        }.toMap()
    }

    /** "xl/worksheets" + "../drawings/drawing1.xml" → "xl/drawings/drawing1.xml". */
    private fun resolve(folder: String, target: String): String {
        if (target.startsWith("/")) return target.trimStart('/')
        val parts = folder.split('/').filter { it.isNotEmpty() }.toMutableList()
        for (segment in target.split('/').filter { it.isNotEmpty() }) {
            when (segment) {
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                "." -> Unit
                else -> parts += segment
            }
        }
        return parts.joinToString("/")
    }

    /** Las imágenes del dibujo de la hoja: su celda de anclaje, sus bytes y el recorte. Un dibujo que no se puede leer no impide leer las celdas. */
    private fun readImages(zip: Map<String, ByteArray>, sheetPath: String, sheetDoc: Document, images: MutableList<XlsxImage>) {
        try {
            val drawingId = elements(sheetDoc.documentElement, MAIN, "drawing").firstOrNull()?.getAttributeNS(REL, "id")?.ifEmpty { null } ?: return
            val drawingPath = partRelationships(zip, sheetPath)[drawingId] ?: return
            val drawing = zip[drawingPath]?.let(::parse) ?: return
            val media = partRelationships(zip, drawingPath)
            var anchor = drawing.documentElement.firstChild
            while (anchor != null) {
                val element = anchor as? Element
                anchor = anchor.nextSibling
                if (element == null || element.namespaceURI != SHEET_DRAWING || element.localName !in setOf("twoCellAnchor", "oneCellAnchor")) continue
                val blipFill = elements(element, SHEET_DRAWING, "pic").firstOrNull()?.let { elements(it, SHEET_DRAWING, "blipFill").firstOrNull() } ?: continue
                val embed = elements(blipFill, DRAWING_MAIN, "blip").firstOrNull()?.getAttributeNS(REL, "embed")?.ifEmpty { null } ?: continue
                val mediaPath = media[embed] ?: continue
                val content = zip[mediaPath] ?: continue
                val from = elements(element, SHEET_DRAWING, "from").firstOrNull()
                val to = elements(element, SHEET_DRAWING, "to").firstOrNull() ?: from
                val crop = elements(blipFill, DRAWING_MAIN, "srcRect").firstOrNull()
                images += XlsxImage(
                    // En el dibujo las filas y columnas empiezan en 0
                    fromRow = anchorIndex(from, "row") + 1,
                    fromColumn = anchorIndex(from, "col") + 1,
                    toRow = anchorIndex(to, "row") + 1,
                    toColumn = anchorIndex(to, "col") + 1,
                    mediaPath = mediaPath,
                    content = content,
                    cropLeft = cropFraction(crop, "l"),
                    cropTop = cropFraction(crop, "t"),
                    cropRight = cropFraction(crop, "r"),
                    cropBottom = cropFraction(crop, "b"),
                )
            }
        } catch (_: XlsxException) {
            // Las celdas sí se leen
        }
    }

    private fun anchorIndex(marker: Element?, name: String): Int = (elements(marker, SHEET_DRAWING, name).firstOrNull()?.textContent?.trim()?.toIntOrNull() ?: 0).coerceIn(0, MAX_ROWS)

    /** El recorte en milésimas de por ciento ("50441" = 50.441 %) como fracción entre 0 y 1. */
    private fun cropFraction(srcRect: Element?, side: String): Double =
        srcRect?.getAttribute(side)?.toIntOrNull()?.let { (it / 100000.0).coerceIn(0.0, 1.0) } ?: 0.0
}
