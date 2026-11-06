package io.github.marioalr.tpvca.domain.consultation

import io.github.marioalr.tpvca.domain.business.BusinessReportRules
import io.github.marioalr.tpvca.domain.model.ClinicalService
import io.github.marioalr.tpvca.domain.model.Product
import io.github.marioalr.tpvca.domain.search.TextSearch
import java.math.BigDecimal

/**
 * La cuenta de la consulta (`Services/Rules/ConsultationAccountRules.cs`): el honorario, el total y los textos de un renglón. Los renglones y cómo se suman y se quitan están en
 * `:sync` (`rules/consultation/AccountLines`), que es donde se pueden serializar. Vectores: `contracts/golden/consultation-account.json`.
 */
object ConsultationAccountRules {
    /** El código del servicio que es el honorario si existe. */
    const val FEE_SERVICE_CODE = BusinessReportRules.FEE_SERVICE_CODE

    /** El nombre del honorario sin un servicio de consulta en el catálogo. */
    const val DEFAULT_FEE_NAME = "Consulta Médica General"

    /** El honorario sin un servicio de consulta en el catálogo. */
    val DEFAULT_FEE: BigDecimal = BigDecimal("300.00")

    /** El código de un cargo libre que no trae uno. */
    const val FREE_LINE_CODE = "CARGO"

    /**
     * El servicio que se cobra como honorario, entre los activos: el que usaba el equipo que envió la consulta ([overrideSyncId]; la PC lo guarda con su Id local), el de código
     * SERV-001, el primero de categoría "Consulta" o el primero cuyo nombre contiene "Consulta" (sin distinguir mayúsculas; los acentos cuentan); `null` si ninguno.
     */
    fun feeService(services: Iterable<ClinicalService>, overrideSyncId: String?): ClinicalService? {
        val active = services.filter { it.isActive }
        return overrideSyncId?.let { id -> active.firstOrNull { it.syncId.equals(id, ignoreCase = true) } }
            ?: active.firstOrNull { it.code.equals(FEE_SERVICE_CODE, ignoreCase = true) }
            ?: active.firstOrNull { it.category.equals("Consulta", ignoreCase = true) }
            ?: active.firstOrNull { it.name.contains("Consulta", ignoreCase = true) }
    }

    fun feeName(feeService: ClinicalService?): String = feeService?.name ?: DEFAULT_FEE_NAME

    fun feeAmount(feeService: ClinicalService?): BigDecimal = feeService?.price ?: DEFAULT_FEE

    /** Lo que se cobrará al terminar: el honorario más cada renglón (cantidad × precio). */
    fun total(feeAmount: BigDecimal, subtotals: Iterable<BigDecimal>): BigDecimal = subtotals.fold(feeAmount, BigDecimal::add)

    /** La "categoría" (genérico) que lleva el renglón de un servicio: "Servicio Clínico • Curaciones". */
    fun serviceLineGeneric(category: String?): String = "Servicio Clínico • ${category.orEmpty()}"

    /** La "presentación" que lleva el renglón de un servicio del catálogo: "Duración: 20 min". */
    fun serviceLinePresentation(minutes: Int): String = "Duración: $minutes min"

    /** El código de un cargo libre: el que trae o "CARGO". */
    fun freeChargeCode(code: String?): String = if (code.isNullOrBlank()) FREE_LINE_CODE else code

    /**
     * "Se surte aquí": cuántas piezas carga un medicamento recetado (su cantidad recomendada, 1 como mínimo), o `null` si no se carga porque no está en el inventario (se receta
     * igual). Revisión 20: las existencias ya no cuentan; si no alcanzan, se carga igual y se avisa.
     */
    fun prescriptionChargeQuantity(recommendedQuantity: Int, product: Product?): Int? = if (product == null) null else maxOf(1, recommendedQuantity)

    /**
     * "Se surte aquí" por defecto al agregar un medicamento a la receta (decisión del médico del 2026-10-22): marcado si el producto tiene existencias (más de 0, o piezas
     * disponibles, también las del paquete abierto); desmarcado con 0 o en negativo, y sin producto.
     */
    fun dispenseByDefault(product: Product?): Boolean = product != null && (product.currentStock > 0 || product.availableIndividualUnits > 0)

    /**
     * El Id de un cargo libre (`HandoffPayloadCodec.FreeChargeId`): sale del nombre normalizado y es igual en todos los equipos, de −1000000 a −1999999. En esta app un cargo libre
     * no tiene otro Id: con esto se reconoce su renglón y se lee el `ChargedLineId` negativo que escribió una PC.
     */
    fun freeChargeId(name: String): Int {
        var hash = 17
        for (c in TextSearch.normalize(name)) hash = hash * 31 + c.code
        return -(1_000_000 + ((hash.toLong() and 0xFFFFFFFFL) % 1_000_000L).toInt())
    }
}
