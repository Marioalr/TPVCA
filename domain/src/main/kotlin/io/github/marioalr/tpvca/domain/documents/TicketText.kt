package io.github.marioalr.tpvca.domain.documents

import io.github.marioalr.tpvca.domain.model.ClinicProfile
import io.github.marioalr.tpvca.domain.model.SaleReceipt
import io.github.marioalr.tpvca.domain.text.MexicanText
import io.github.marioalr.tpvca.domain.text.NetDecimal
import java.math.BigDecimal
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * El ticket en 42 columnas, renglón por renglón (`Services/Documents/TicketText.cs`): lo mismo que imprime la PC en la impresora
 * térmica. El celular lo comparte como texto o PDF. Vectores: `contracts/golden/ticket-text.json`.
 */
object TicketText {
    const val WIDTH = 42
    val THIN_RULE: String = "-".repeat(WIDTH)
    val THICK_RULE: String = "=".repeat(WIDTH)

    const val DEFAULT_CLINIC_NAME = "CONSULTORIO MÉDICO TPVC"
    const val DEFAULT_DOCTOR_NAME = "Dr. Titular"

    val FOOTER_LINES: List<String> = listOf("¡Gracias por su visita y confianza!", "Comprobante de atención médica y farmacia")

    // Cant (4) · Descripción (17) · P.U. (9) · Total (9), con un espacio entre columnas
    private const val QUANTITY_WIDTH = 4
    private const val DESCRIPTION_WIDTH = 17
    private const val MONEY_WIDTH = 9

    private val DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

    val ITEM_HEADERS: List<String> = listOf("Cant", "Descripción", "P.U.", "Total")

    fun headerLines(profile: ClinicProfile): List<String> {
        val clinicName = if (!profile.clinicName.isNullOrBlank()) profile.clinicName else DEFAULT_CLINIC_NAME
        val doctorName = if (!profile.doctorName.isNullOrBlank()) profile.doctorName else DEFAULT_DOCTOR_NAME
        val license = if (!profile.professionalLicense.isNullOrBlank()) profile.professionalLicense else ""
        val lines = mutableListOf(clinicName.uppercase(Locale.ROOT))
        lines.add(if (license.isBlank()) doctorName else "$doctorName • Céd. $license")
        if (!profile.address.isNullOrBlank()) lines.add(profile.address)
        if (!profile.phone.isNullOrBlank()) lines.add("Tel: ${profile.phone}")
        return lines
    }

    fun metaLines(receipt: SaleReceipt): List<String> {
        val lines = mutableListOf("FOLIO:    ${receipt.folio}")
        if (!receipt.originalFolio.isNullOrBlank()) lines.add("FOLIO SIN CONEXIÓN: ${receipt.originalFolio}")
        lines.add("FECHA:    ${receipt.date.format(DATE)}")
        lines.add("PACIENTE: ${receipt.patientName}")
        lines.add("PAGO:     ${receipt.paymentMethod}")
        lines.add("ATENDIÓ:  ${receipt.cashier}")
        return lines
    }

    /** "$" + F2 sin miles (0.125 → "$0.13"). */
    fun money(amount: BigDecimal): String = "$" + NetDecimal.fixed(amount, 2)

    fun totalLines(receipt: SaleReceipt): List<String> = listOf(
        "TOTAL DE ARTÍCULOS: ${receipt.totalItems}",
        "TOTAL PAGADO:       $${MexicanText.n2(receipt.totalAmount)} MXN",
    )

    fun lines(receipt: SaleReceipt, profile: ClinicProfile?): List<String> {
        val lines = mutableListOf<String>()
        for (line in headerLines(profile ?: ClinicProfile())) lines.addAll(wrap(line, WIDTH).map(::center))
        lines.add(THIN_RULE)
        lines.addAll(metaLines(receipt).flatMap { wrap(it, WIDTH) })
        lines.add(THICK_RULE)
        lines.add(itemRow(ITEM_HEADERS[0], ITEM_HEADERS[1], ITEM_HEADERS[2], ITEM_HEADERS[3]))
        for (item in receipt.items) {
            val description = wrap(item.name, DESCRIPTION_WIDTH)
            lines.add(itemRow(item.quantity.toString(), description[0], money(item.unitPrice), money(item.subtotal)))
            for (rest in description.drop(1)) lines.add(itemRow("", rest, "", ""))
        }
        lines.add(THIN_RULE)
        lines.addAll(totalLines(receipt))
        lines.add(THICK_RULE)
        for (line in FOOTER_LINES) lines.addAll(wrap(line, WIDTH).map(::center))
        return lines
    }

    /** El ticket completo, un renglón por línea y "\n" al final. */
    fun build(receipt: SaleReceipt, profile: ClinicProfile?): String = lines(receipt, profile).joinToString("\n") + "\n"

    private fun itemRow(quantity: String, description: String, unitPrice: String, total: String): String =
        (quantity.padStart(QUANTITY_WIDTH) + " " + description.padEnd(DESCRIPTION_WIDTH) + " " + unitPrice.padStart(MONEY_WIDTH) + " " + total.padStart(MONEY_WIDTH))
            .trimEnd()

    private fun center(line: String): String = if (line.length >= WIDTH) line else " ".repeat((WIDTH - line.length) / 2) + line

    /** Parte por palabras a [width]; una palabra más larga se corta. */
    fun wrap(text: String?, width: Int): List<String> {
        val lines = mutableListOf<String>()
        var rest = text.orEmpty().trimEnd()
        while (rest.length > width) {
            val cut = rest.lastIndexOf(' ', width)
            if (cut > 0 && rest.substring(0, cut).isNotBlank()) {
                lines.add(rest.substring(0, cut).trimEnd())
                rest = rest.substring(cut + 1).trimStart()
            } else {
                lines.add(rest.substring(0, width))
                rest = rest.substring(width).trimStart()
            }
        }
        lines.add(rest)
        return lines
    }
}
