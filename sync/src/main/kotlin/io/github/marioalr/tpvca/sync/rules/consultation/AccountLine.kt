package io.github.marioalr.tpvca.sync.rules.consultation

import io.github.marioalr.tpvca.domain.consultation.ConsultationAccountRules
import io.github.marioalr.tpvca.domain.model.ClinicalService
import io.github.marioalr.tpvca.domain.model.Product
import io.github.marioalr.tpvca.domain.text.NetDecimal
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal

/** Un `BigDecimal` como texto invariante con su escala ("120.50"). Al leer acepta también un número: así viene en la copia de una consulta que manda la PC (K8). */
object DecimalAsTextSerializer : KSerializer<BigDecimal> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("DecimalAsText", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: BigDecimal) = encoder.encodeString(NetDecimal.text(value))

    override fun deserialize(decoder: Decoder): BigDecimal {
        val json = decoder as? JsonDecoder ?: return NetDecimal.parse(decoder.decodeString())
        val element = json.decodeJsonElement()
        val text = (element as? JsonPrimitive)?.content ?: throw SerializationException("Se esperaba un decimal y llegó $element")
        return NetDecimal.parse(text)
    }
}

/**
 * Un renglón de la cuenta de la consulta (`ConsultationProductItem` de la PC, con los nombres que lleva en la copia que viaja a otro equipo). Esta app no tiene Id locales: el renglón
 * se reconoce por [lineKind], [catalogSyncId] (`null` en un cargo libre, que se reconoce por su nombre) e [isIndividualUnits]. [addedAt] es la hora del consultorio, ISO 8601 sin zona.
 */
@Serializable
data class AccountLine(
    @SerialName("LineKind") val lineKind: String,
    @SerialName("CatalogSyncId") val catalogSyncId: String? = null,
    @SerialName("IsIndividualUnits") val isIndividualUnits: Boolean = false,
    @SerialName("Code") val code: String = "",
    @SerialName("Name") val name: String = "",
    @SerialName("GenericName") val genericName: String? = null,
    @SerialName("Presentation") val presentation: String? = null,
    @SerialName("Unit") val unit: String = "Piezas",
    @SerialName("UnitPrice") @Serializable(with = DecimalAsTextSerializer::class) val unitPrice: BigDecimal = NetDecimal.ZERO,
    @SerialName("Quantity") val quantity: Int = 1,
    @SerialName("AddedAt") val addedAt: String = "",
) {
    val subtotal: BigDecimal get() = BigDecimal(quantity).multiply(unitPrice)
}

/**
 * A qué renglón de la cuenta se refiere un cargo (el `SupplyLine` y los Id de renglón de la PC): un producto o servicio por su SyncId, o un cargo libre por su Id, que sale de un nombre
 * y vale igual en todos los equipos. [name] es el nombre del insumo o servicio de la hoja: un cargo libre puede llevar el Id de otro nombre (el renglón ya se llamaba distinto).
 */
data class LineRef(val kind: String, val syncId: String? = null, val freeChargeId: Int? = null, val name: String? = null) {
    val isFree: Boolean get() = kind == AccountLines.FREE

    /** Es este renglón, sea cual sea su modo (paquetes o piezas sueltas). */
    fun matches(line: AccountLine): Boolean {
        if (line.lineKind != kind) return false
        if (!isFree) return syncId != null && syncId.equals(line.catalogSyncId, ignoreCase = true)
        val id = ConsultationAccountRules.freeChargeId(line.name)
        return id == freeChargeId || (name != null && id == ConsultationAccountRules.freeChargeId(name))
    }

    companion object {
        fun product(product: Product): LineRef = LineRef(AccountLines.PRODUCT, product.syncId.ifEmpty { null })

        fun product(syncId: String): LineRef = LineRef(AccountLines.PRODUCT, syncId)

        fun service(service: ClinicalService): LineRef = LineRef(AccountLines.SERVICE, service.syncId.ifEmpty { null })

        /** El cargo libre de un nombre. */
        fun free(name: String?): LineRef = LineRef(AccountLines.FREE, null, ConsultationAccountRules.freeChargeId(name.orEmpty()), name.orEmpty())

        /** El cargo libre con un Id que ya trae el renglón de la hoja (un `ChargedLineId` negativo). */
        fun free(id: Int, name: String?): LineRef = LineRef(AccountLines.FREE, null, id, name.orEmpty())
    }
}

/** Cómo se suman y se quitan los renglones (`ConsultationAccountRules.AddLine` y `RemoveLine`) sobre una lista que no cambia: regresan la lista nueva, o `null` si nada cambió. */
object AccountLines {
    const val PRODUCT = "producto"
    const val SERVICE = "servicio"
    const val FREE = "libre"

    private val JSON = Json { ignoreUnknownKeys = true }
    private val LIST = ListSerializer(AccountLine.serializer())

    fun encode(lines: List<AccountLine>): String = JSON.encodeToString(LIST, lines)

    fun decode(text: String): List<AccountLine> = JSON.decodeFromString(LIST, text)

    /**
     * Suma piezas: al renglón que ya es de [line] (solo cambia su cantidad; el nombre y el precio se quedan) o en uno nuevo al final. `null` con cantidad 0 o negativa. Las piezas
     * sueltas de un paquete van en su propio renglón, aparte de los paquetes completos del mismo producto.
     */
    fun add(lines: List<AccountLine>, ref: LineRef, line: AccountLine): List<AccountLine>? {
        if (line.quantity <= 0) return null
        val index = lines.indexOfFirst { ref.matches(it) && it.isIndividualUnits == line.isIndividualUnits }
        if (index < 0) return lines + line
        return lines.toMutableList().also { it[index] = lines[index].copy(quantity = lines[index].quantity + line.quantity) }
    }

    /**
     * Quita piezas del renglón: si tiene más se reduce y si no sale de la cuenta. `null` si no hay renglón o la cantidad no es positiva. Con [isIndividualUnits] se prefiere el renglón
     * de ese modo y, si no hay, cualquiera de ese producto.
     */
    fun remove(lines: List<AccountLine>, ref: LineRef, quantity: Int, isIndividualUnits: Boolean? = null): List<AccountLine>? {
        var index = lines.indexOfFirst { ref.matches(it) && (isIndividualUnits == null || it.isIndividualUnits == isIndividualUnits) }
        if (index < 0) index = lines.indexOfFirst(ref::matches)
        if (index < 0 || quantity <= 0) return null
        val line = lines[index]
        return lines.toMutableList().also { if (line.quantity > quantity) it[index] = line.copy(quantity = line.quantity - quantity) else it.removeAt(index) }
    }
}
