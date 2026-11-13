package io.github.marioalr.tpvca.domain.imports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Lo que lee la importación del Excel (`DataImportService` de la PC), con los casos de sus comentarios y datos inventados. */
class ExcelImportTest {
    private fun sheet(name: String, vararg cells: Triple<Int, Int, String>) = XlsxSheet(name).apply { for ((r, c, v) in cells) set(r, c, v, null) }

    @Test
    fun genericoYPresentacion() {
        assertEquals("Paracetamol" to "500 mg tabletas", ExcelImport.drugDetails("Paracetamol 500 mg tabletas"))
        assertEquals("Amoxicilina" to "250 mg/5 ml suspensión", ExcelImport.drugDetails("Amoxicilina 250 mg/5 ml suspensión"))
        assertEquals("Ketoconazol" to "crema al 2", ExcelImport.drugDetails("Ketoconazol crema al 2"))
        assertEquals("Vitaminas" to null, ExcelImport.drugDetails("Vitaminas"))
        assertEquals(null to null, ExcelImport.drugDetails(" "))
    }

    @Test
    fun mlPorKiloDeLaFormula() {
        assertEquals(0.46875, ExcelImport.parseMlPerKg("(C2*15)/32", "", "")!!, 1e-12)
        assertEquals(0.5, ExcelImport.parseMlPerKg("C5 * 0.5", "", "")!!, 1e-12)
        assertEquals(0.25, ExcelImport.parseMlPerKg(null, "8", "2")!!, 1e-12)
        assertNull(ExcelImport.parseMlPerKg("SUMA(A1)", "0", "2"))
    }

    @Test
    fun plantillas() {
        assertEquals("• Reposo\n• Líquidos", ExcelImport.normalizeTemplateText("•Reposo\r\n\n•   Líquidos\t "))
        assertEquals("", ExcelImport.normalizeTemplateText("  "))
        val long = "• " + "a".repeat(70)
        assertEquals("a".repeat(57) + "…", ExcelImport.templateTitle(long))
        assertEquals("Reposo", ExcelImport.templateTitle("• Reposo\n• Más"))
    }

    @Test
    fun posologiaPorVerboOPorLasConocidas() {
        assertTrue(ExcelImport.isPosologyLine("Vía oral cada 8 h", emptyList()))
        assertTrue(ExcelImport.isPosologyLine("1 tableta cada 8 horas", emptyList()))
        assertFalse(ExcelImport.isPosologyLine("Paracetamol 500 mg", emptyList()))
        assertTrue(ExcelImport.isPosologyLine("Aplicar en la zona dos veces", emptyList()))
        assertFalse("«aplicación» no empieza con «aplicar»", ExcelImport.isPosologyLine("aplicación tópica", emptyList()))
        assertTrue(ExcelImport.isPosologyLine("Cucharadita cada 8 horas por 5 días", listOf("cucharadita cada 8 horas por 3 dias")))
    }

    @Test
    fun laFechaDeLaReceta() {
        val today = LocalDate.of(2026, 11, 13)
        assertEquals(LocalDate.of(2026, 1, 15), ExcelImport.recetaDate("46037", today))
        assertEquals(LocalDate.of(2026, 3, 9), ExcelImport.recetaDate("9/3/2026", today))
        assertEquals(LocalDate.of(2026, 3, 9), ExcelImport.recetaDate("09/03/2026 00:00:00", today))
        assertEquals(today, ExcelImport.recetaDate("pronto", today))
    }

    @Test
    fun elValorDespuesDeLaEtiqueta() {
        val s = sheet("RECETA", Triple(1, 1, "Paciente:"), Triple(1, 2, "Ana Ejemplo"), Triple(1, 3, "Edad:"), Triple(1, 4, "Fecha:"), Triple(2, 1, "Alergias: ninguna"))
        assertEquals("Ana Ejemplo", ExcelImport.valueAfterLabel(s, 1, "Paciente"))
        assertEquals("Una etiqueta sin valor", "", ExcelImport.valueAfterLabel(s, 1, "Edad"))
        assertEquals("ninguna", ExcelImport.valueAfterLabel(s, 2, "Alergias"))
        assertEquals(1, ExcelImport.findRowStartingWith(s, "paciente"))
        assertEquals(0, ExcelImport.findRowStartingWith(s, "Talla"))
    }

    @Test
    fun lasImagenesDelMembrete() {
        val s = XlsxSheet("RECETA")
        val bytes = byteArrayOf(1)
        val crest = XlsxImage(1, 2, 3, 3, "a", bytes)
        val left = XlsxImage(2, 7, 3, 9, "b", bytes)
        val right = XlsxImage(2, 10, 3, 12, "c", bytes)
        val footer = XlsxImage(30, 10, 31, 11, "d", bytes)
        val empty = XlsxImage(1, 1, 2, 2, "e", ByteArray(0))
        s.images += listOf(left, footer, crest, right, empty)
        val (c, h) = ExcelImport.letterheadImages(s)
        assertSame(crest, c)
        assertSame(right, h)
    }

    @Test
    fun csvConComillasYSeparadores() {
        assertEquals(listOf("A", "B, c", "d\"e", ""), ExcelImport.splitCsvLine("A, \"B, c\" ,\"d\"\"e\",", ','))
        val plan = ExcelImport.csvPlan("Nombre\tClave\nParacetamol\tP-1\n")!!
        assertEquals(listOf(CatalogLine("Paracetamol", "P-1")), plan.catalog)
        assertTrue(plan.isCsv)
    }

    @Test
    fun elArchivoPorSuExtension() {
        assertEquals(ExcelImport.Kind.Xlsx, ExcelImport.kindOf("Catálogo.XLSX"))
        assertEquals(ExcelImport.Kind.Csv, ExcelImport.kindOf("lista.txt"))
        assertNull(ExcelImport.kindOf("receta.xls"))
        assertEquals("Formato no soportado (.xls). Seleccione un archivo Excel (.xlsx) o texto delimitado (.csv).", ExcelImport.unsupported("receta.xls"))
    }

    @Test
    fun unArchivoQueNoEsUnLibro() {
        val error = runCatching { Xlsx.read("no es un zip".toByteArray()) }.exceptionOrNull()
        assertTrue(error is XlsxException)
        assertEquals(27, Xlsx.columnIndex("AA"))
        assertEquals(1, Xlsx.columnIndex("a"))
    }
}
