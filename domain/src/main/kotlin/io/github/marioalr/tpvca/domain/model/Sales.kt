package io.github.marioalr.tpvca.domain.model

import io.github.marioalr.tpvca.domain.text.NetDecimal
import java.math.BigDecimal
import java.time.LocalDateTime

/** Una venta (`Sales`) con sus renglones, para el corte de caja. */
data class Sale(
    val id: Int = 0,
    val date: LocalDateTime = LocalDateTime.MIN,
    val total: BigDecimal = NetDecimal.ZERO,
    val paymentMethod: String = "Efectivo",
    val isCancelled: Boolean = false,
    val items: List<SaleItem> = emptyList(),
)

/** Un renglón de una venta (`SaleItems`): lo que leen el corte de caja y el ticket. */
data class SaleItem(
    val isService: Boolean = false,
    val quantity: Int = 0,
    val unitPrice: BigDecimal = NetDecimal.ZERO,
    val subtotal: BigDecimal = NetDecimal.ZERO,
)

/** Un renglón del ticket (`Models/SaleReceipt.cs`): su subtotal es cantidad × precio. */
data class SaleReceiptItem(
    val name: String = "",
    val quantity: Int = 0,
    val unitPrice: BigDecimal = NetDecimal.ZERO,
) {
    val subtotal: BigDecimal get() = BigDecimal(quantity).multiply(unitPrice)
}

/** El ticket de una venta (`Models/SaleReceipt.cs`). [date] es la hora del consultorio. */
data class SaleReceipt(
    val folio: String = "",
    val originalFolio: String? = null,
    val date: LocalDateTime = LocalDateTime.MIN,
    val patientName: String = "Público General",
    val paymentMethod: String = "Efectivo",
    val cashier: String = "Dr. Titular / Consultorio",
    val items: List<SaleReceiptItem> = emptyList(),
) {
    val totalItems: Int get() = items.sumOf { it.quantity }

    val totalAmount: BigDecimal get() = items.fold(NetDecimal.ZERO) { sum, item -> sum.add(item.subtotal) }
}

/** El membrete del consultorio (`ClinicProfiles`): lo que leen la receta y el ticket. */
data class ClinicProfile(
    val clinicName: String? = null,
    val doctorName: String? = null,
    val professionalLicense: String? = null,
    val university: String? = null,
    val phone: String? = null,
    val address: String? = null,
)
