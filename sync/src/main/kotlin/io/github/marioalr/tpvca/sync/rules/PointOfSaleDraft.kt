package io.github.marioalr.tpvca.sync.rules

import io.github.marioalr.tpvca.sync.rules.consultation.DecimalAsTextSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigDecimal

/**
 * La cuenta del punto de venta que se está armando (K9), en `Draft:Sale` de la base cifrada: sobrevive a que Android cierre la app. Lleva el `SyncId` del ticket que va a cobrar: si el
 * proceso muere después de cobrar, ese ticket ya existe y la cuenta guardada sobra. La PC no la guarda (su cuenta vive en la memoria): es de la tablet, que Android puede cerrar en cualquier momento.
 */
@Serializable
data class PointOfSaleDraft(
    @SerialName("Version") val version: Int = 1,
    @SerialName("SaleSyncId") val saleSyncId: String,
    @SerialName("PatientName") val patientName: String = "",
    @SerialName("PaymentMethod") val paymentMethod: String = "",
    @SerialName("CashReceived") val cashReceived: String = "",
    @SerialName("Items") val items: List<Line> = emptyList(),
) {
    /** Un renglón de la cuenta (`CartItem`). */
    @Serializable
    data class Line(
        @SerialName("ProductId") val productId: Int = 0,
        @SerialName("CatalogSyncId") val catalogSyncId: String? = null,
        @SerialName("Code") val code: String = "",
        @SerialName("Name") val name: String = "",
        @SerialName("GenericName") val genericName: String? = null,
        @SerialName("Presentation") val presentation: String? = null,
        @SerialName("Unit") val unit: String = "Piezas",
        @SerialName("AvailableStock") val availableStock: Int = 0,
        @SerialName("UnitPrice") @Serializable(with = DecimalAsTextSerializer::class) val unitPrice: BigDecimal = BigDecimal.ZERO,
        @SerialName("IsService") val isService: Boolean = false,
        @SerialName("IsIndividualUnits") val isIndividualUnits: Boolean = false,
        @SerialName("Quantity") val quantity: Int = 1,
        @SerialName("RequiresRefrigeration") val requiresRefrigeration: Boolean = false,
    )

    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** `null` si no se entiende: una cuenta que no se puede leer no detiene nada. */
        fun decode(text: String?): PointOfSaleDraft? = text?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() }?.takeIf { it.saleSyncId.isNotBlank() }
    }
}
