package io.github.marioalr.tpvca.sync

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

/**
 * Un libro de Excel **inventado** con el formato de cinco hojas del consultorio (MEDICAMENTOS, POSOLOGIA, M. GRALES, DOSIS PEDIA y RECETA, como el que importa `DataImportService` de la PC), para
 * probar la importación sin datos de nadie. Las celdas son texto en línea o cadenas compartidas, con fórmulas en DOSIS PEDIA, y la hoja RECETA trae el escudo, la imagen del encabezado (recortada a
 * la mitad) y un icono del pie.
 */
object TestWorkbook {
    class Cell(val ref: String, val value: String, val formula: String? = null, val number: Boolean = false, val shared: Boolean = false)

    class Picture(val fromCol: Int, val fromRow: Int, val png: ByteArray, val cropLeft: Int = 0)

    class Sheet(val name: String, val cells: List<Cell>, val pictures: List<Picture> = emptyList())

    /** Un PNG de [width] × [height] de un solo color. */
    fun png(width: Int, height: Int, rgb: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until width) for (y in 0 until height) image.setRGB(x, y, rgb)
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    val CREST: ByteArray by lazy { png(8, 8, 0x1D4ED8) }
    val HEADER: ByteArray by lazy { png(20, 6, 0x16A34A) }
    private val ICON: ByteArray by lazy { png(4, 4, 0x000000) }

    /** El libro de ejemplo, con lo que se le pida cambiar. */
    fun clinic(doctor: String = "Dra. Ejemplo Prueba Ruiz", patient: String = "Paciente Inventado Gómez"): ByteArray = build(
        listOf(
            Sheet(
                "MEDICAMENTOS",
                listOf(
                    Cell("A1", "Clave"), Cell("B1", "Medicamento", shared = true),
                    Cell("A2", "1308"), Cell("B2", "Paracetamol 500 mg tabletas"),
                    Cell("A3", ""), Cell("B3", "Ambroxol jarabe 30 mg/5 ml"),
                    Cell("A4", "1308"), Cell("B4", "Loratadina 10 mg tabletas"),
                    Cell("A5", "MED-020"), Cell("B5", "Crema humectante"),
                ),
            ),
            Sheet("POSOLOGIA", listOf(Cell("A1", "POSOLOGÍA"), Cell("A2", "Tomar 1 tableta cada 8 horas"), Cell("A3", "  Tomar   1 tableta\tcada 12 horas  "), Cell("A4", "tomar 1 tableta cada 8 horas"))),
            Sheet("M. GRALES", listOf(Cell("A1", "•Reposo relativo\n•  Abundantes líquidos"), Cell("A2", ""))),
            Sheet(
                "DOSIS PEDIA",
                listOf(
                    Cell("A1", "MEDICAMENTO"), Cell("B1", "DOSIS"), Cell("C1", "PESO"), Cell("D1", "ML"), Cell("E1", "GOTAS"),
                    Cell("A2", "Paracetamol gotas"), Cell("B2", "15 mg/kg"), Cell("C2", "10", number = true), Cell("D2", "4.6875", "(C2*15)/32", number = true), Cell("E2", "93.75", "D2*20", number = true),
                    Cell("A3", "Ibuprofeno suspensión"), Cell("B3", "10 mg/kg"), Cell("C3", "8", number = true), Cell("D3", "4", number = true),
                ),
            ),
            Sheet(
                "RECETA",
                listOf(
                    Cell("B1", doctor), Cell("B2", "Universidad de Ejemplo"), Cell("B3", "Céd. Prof. 1234567"),
                    Cell("A6", "Paciente:"), Cell("B6", patient), Cell("E6", "Edad:"), Cell("F6", "34 años"), Cell("H6", "Fecha:"), Cell("I6", "46037", number = true),
                    Cell("A7", "Alergias: Penicilina"), Cell("C7", "T/A:"), Cell("D7", "120/80"), Cell("E7", "Temp:"), Cell("F7", "36.5"), Cell("G7", "Peso:"), Cell("H7", "70 kg"),
                    Cell("I7", "SpO2:"), Cell("J7", "0.97", number = true),
                    Cell("A9", "Paracetamol 500 mg tabletas"), Cell("A10", "Tomar 1 tableta cada 8 horas"), Cell("A11", "Vía oral por 3 días"),
                    Cell("A12", "Loratadina 10 mg tabletas"), Cell("F9", "Medidas generales:"), Cell("F10", "Reposo"), Cell("F11", "Líquidos abundantes"),
                    Cell("A20", "55 1234 5678"), Cell("C20", "Calle Ejemplo 123, Colonia Centro"),
                ),
                listOf(Picture(0, 0, CREST), Picture(9, 1, HEADER, cropLeft = 50000), Picture(0, 19, ICON)),
            ),
        ),
    )

    fun build(sheets: List<Sheet>): ByteArray {
        val shared = sheets.flatMap { s -> s.cells.filter { it.shared }.map { it.value } }.distinct()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, text: String) = put(zip, name, text.toByteArray(Charsets.UTF_8))
            put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""")
            put(
                "xl/workbook.xml",
                """<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="$MAIN" xmlns:r="$REL"><sheets>""" +
                    sheets.withIndex().joinToString("") { (i, s) -> """<sheet name="${esc(s.name)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""" } + "</sheets></workbook>",
            )
            put(
                "xl/_rels/workbook.xml.rels",
                """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="$PKG">""" +
                    sheets.indices.joinToString("") { """<Relationship Id="rId${it + 1}" Type="$REL/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""" } +
                    """<Relationship Id="rIdS" Type="$REL/sharedStrings" Target="sharedStrings.xml"/></Relationships>""",
            )
            put("xl/sharedStrings.xml", """<?xml version="1.0" encoding="UTF-8"?><sst xmlns="$MAIN">""" + shared.joinToString("") { "<si><r><t>${esc(it)}</t></r></si>" } + "</sst>")
            var image = 0
            for ((i, sheet) in sheets.withIndex()) {
                val rows = sheet.cells.groupBy { Regex("\\d+").find(it.ref)!!.value.toInt() }.toSortedMap()
                val xml = StringBuilder("""<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="$MAIN" xmlns:r="$REL"><sheetData>""")
                for ((row, cells) in rows) {
                    xml.append("""<row r="$row">""")
                    for (c in cells) {
                        val formula = c.formula?.let { "<f>${esc(it)}</f>" }.orEmpty()
                        when {
                            c.shared -> xml.append("""<c r="${c.ref}" t="s">$formula<v>${shared.indexOf(c.value)}</v></c>""")
                            c.number -> xml.append("""<c r="${c.ref}">$formula<v>${c.value}</v></c>""")
                            else -> xml.append("""<c r="${c.ref}" t="inlineStr">$formula<is><t xml:space="preserve">${esc(c.value)}</t></is></c>""")
                        }
                    }
                    xml.append("</row>")
                }
                xml.append("</sheetData>")
                if (sheet.pictures.isNotEmpty()) {
                    xml.append("""<drawing r:id="rIdD"/>""")
                    put("xl/worksheets/_rels/sheet${i + 1}.xml.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="$PKG"><Relationship Id="rIdD" Type="$REL/drawing" Target="../drawings/drawing${i + 1}.xml"/></Relationships>""")
                    val drawing = StringBuilder("""<?xml version="1.0" encoding="UTF-8"?><xdr:wsDr xmlns:xdr="$XDR" xmlns:a="$A" xmlns:r="$REL">""")
                    val rels = StringBuilder("""<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="$PKG">""")
                    for ((j, p) in sheet.pictures.withIndex()) {
                        image++
                        put(zip, "xl/media/image$image.png", p.png)
                        rels.append("""<Relationship Id="rIdI$j" Type="$REL/image" Target="../media/image$image.png"/>""")
                        val crop = if (p.cropLeft > 0) """<a:srcRect l="${p.cropLeft}"/>""" else ""
                        drawing.append(
                            """<xdr:twoCellAnchor><xdr:from><xdr:col>${p.fromCol}</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>${p.fromRow}</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from>""" +
                                """<xdr:to><xdr:col>${p.fromCol + 1}</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>${p.fromRow + 2}</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:to>""" +
                                """<xdr:pic><xdr:nvPicPr><xdr:cNvPr id="${j + 2}" name="Imagen $j"/><xdr:cNvPicPr/></xdr:nvPicPr><xdr:blipFill><a:blip r:embed="rIdI$j"/>$crop<a:stretch/></xdr:blipFill></xdr:pic>""" +
                                """<xdr:clientData/></xdr:twoCellAnchor>""",
                        )
                    }
                    put("xl/drawings/drawing${i + 1}.xml", drawing.append("</xdr:wsDr>").toString())
                    put("xl/drawings/_rels/drawing${i + 1}.xml.rels", rels.append("</Relationships>").toString())
                }
                put("xl/worksheets/sheet${i + 1}.xml", xml.append("</worksheet>").toString())
            }
        }
        return out.toByteArray()
    }

    private fun put(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun esc(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private const val MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val PKG = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val XDR = "http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing"
    private const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
}
