package io.github.marioalr.tpvca.sync.rules.consultation

import io.github.marioalr.tpvca.domain.model.ClinicalService
import io.github.marioalr.tpvca.domain.model.Product
import io.github.marioalr.tpvca.domain.model.ServiceDefaultProduct

/**
 * Los insumos de un renglón de servicio en la consulta (`Services/Rules/ServiceRowSupplies.cs`, revisión 14): cada renglón cargado lleva su propia lista con lo que cargó y el médico la
 * ajusta solo para esa consulta; la configuración del servicio nunca cambia. Las listas son valores: cada función regresa la lista nueva. Vectores:
 * `contracts/golden/synced-json-columns.json`.
 */
object ServiceRowSupplies {
    /** Piezas de un insumo que se cargan o descuentan: [entry] como quedó, en la posición [index] de la lista si sigue en ella, y su producto si se conoce. */
    data class Pieces(val entry: ServiceSupply, val product: Product?, val count: Int, val index: Int)

    /** La lista como quedó y lo que hay que cargar o descontar, un elemento por insumo, en el orden de la lista. */
    data class Change(val list: List<ServiceSupply>, val pieces: List<Pieces>)

    /** Lo que deja ajustar a mano las piezas de un insumo: [charge] se carga (positivo) o se descuenta (negativo) de su renglón; [warning]: no caben en las existencias, sin bloquear. */
    data class Adjustment(val list: List<ServiceSupply>, val charge: Int, val warning: Boolean)

    /** Piezas que un insumo por defecto carga por cada unidad del servicio (al menos 1). */
    fun piecesPerUnit(defaultProduct: ServiceDefaultProduct): Int = maxOf(1, defaultProduct.quantity)

    /** Un insumo nuevo de la lista, cargado como el producto (su nombre, su precio individual, su clase y su SyncId). */
    fun newEntry(product: Product, pieces: Int): ServiceSupply =
        ServiceSupply(supplyName = product.name, quantity = pieces, unitPrice = product.individualUnitPrice).withLine(LineRef.product(product))

    /** El insumo de la lista es este producto: un cargo de producto (no libre) con su SyncId. */
    fun isProduct(entry: ServiceSupply, product: Product): Boolean =
        entry.chargedLineKind != AccountLines.FREE && product.syncId.isNotEmpty() && product.syncId.equals(entry.chargedLineSyncId, ignoreCase = true)

    /** El insumo de la lista que es este producto, si lo hay. */
    fun find(list: List<ServiceSupply>?, product: Product): ServiceSupply? = list?.firstOrNull { isProduct(it, product) }

    /** La lista con que empieza un renglón: los insumos por defecto del servicio por sus unidades. */
    fun fromDefaults(defaults: Iterable<ServiceDefaultProduct>?, units: Int): List<ServiceSupply> = addUnits(emptyList(), defaults, units).list

    /** Una o más unidades más: cada insumo por defecto suma sus piezas al insumo de la lista del mismo producto, o entra al final. Regresa lo que hay que cargar. */
    fun addUnits(list: List<ServiceSupply>, defaults: Iterable<ServiceDefaultProduct>?, units: Int): Change {
        if (units <= 0) return Change(list, emptyList())
        val out = list.toMutableList()
        val charged = LinkedHashMap<Int, Pair<Product, Int>>()
        for (defaultProduct in defaults ?: emptyList()) {
            val product = defaultProduct.product ?: continue
            val pieces = piecesPerUnit(defaultProduct) * units
            var index = out.indexOfFirst { isProduct(it, product) }
            if (index < 0) {
                out += newEntry(product, 0)
                index = out.size - 1
            }
            out[index] = out[index].copy(quantity = out[index].quantity + pieces)
            charged[index] = product to (charged[index]?.second ?: 0) + pieces
        }
        return Change(out, charged.map { (index, value) -> Pieces(out[index], value.first, value.second, index) })
    }

    /**
     * Una o más unidades menos: cada insumo por defecto descuenta sus piezas del insumo de la lista del mismo producto, hasta lo que tenga; uno que llega a 0 sale de la lista. Lo que el
     * médico agregó a mano se queda. Regresa lo que hay que descontar, en el orden de la lista; [Pieces.index] es la posición antes de sacar los que llegaron a 0.
     */
    fun removeUnits(list: List<ServiceSupply>, defaults: Iterable<ServiceDefaultProduct>?, units: Int): Change {
        if (units <= 0) return Change(list, emptyList())
        val out = list.toMutableList()
        val uncharged = LinkedHashMap<Int, Pair<Product, Int>>()
        for (defaultProduct in defaults ?: emptyList()) {
            val product = defaultProduct.product ?: continue
            val index = out.indexOfFirst { isProduct(it, product) }
            if (index < 0 || out[index].quantity <= 0) continue
            val pieces = minOf(out[index].quantity, piecesPerUnit(defaultProduct) * units)
            out[index] = out[index].copy(quantity = out[index].quantity - pieces)
            uncharged[index] = product to (uncharged[index]?.second ?: 0) + pieces
        }
        val pieces = uncharged.toSortedMap().map { (index, value) -> Pieces(out[index], value.first, value.second, index) }
        return Change(out.filter { it.quantity > 0 }, pieces)
    }

    /**
     * Las piezas nuevas de un insumo, escritas o con + / − y la X; [requested] `null` es agregar un producto del inventario (una pieza más, o entra al final con una). Si suben, se carga
     * todo (revisión 20) y [Adjustment.warning] dice si pasa de sus existencias con lo que ya está en la cuenta ([inAccount]); en 0 piezas el insumo sale.
     */
    fun adjust(list: List<ServiceSupply>, product: Product, requested: Int?, inAccount: Int): Adjustment {
        val current = find(list, product)?.quantity ?: 0
        val difference = (requested ?: (current + 1)) - current
        val warning = difference > 0 && piecesThatFit(difference, product.currentStock, inAccount) < difference
        if (difference == 0) return Adjustment(list, 0, false)
        val out = list.toMutableList()
        var index = out.indexOfFirst { isProduct(it, product) }
        if (index < 0) {
            out += newEntry(product, 0)
            index = out.size - 1
        }
        out[index] = out[index].copy(quantity = out[index].quantity + difference)
        return Adjustment(out.filter { it.quantity > 0 }, difference, warning)
    }

    /** El renglón lo cargó la consulta con ese folio: solo entonces sus unidades y su lista están en esa cuenta. */
    fun isFromConsultation(row: ServiceRow, folio: String?): Boolean = !folio.isNullOrBlank() && row.chargedConsultationFolio == folio

    /** Cuántas de las piezas que se quieren cargar caben: lo que ya está en la cuenta de ese producto más lo que se carga no pasa de sus existencias. */
    fun piecesThatFit(requested: Int, stock: Int, alreadyInAccount: Int): Int = maxOf(0, minOf(requested, stock - alreadyInAccount))

    /** Un renglón sin lista (revisión 13 o anterior) cargó el servicio y sus insumos por defecto: eso descuenta al quitarlo, con los que el servicio tiene hoy. */
    fun legacySupplies(service: ClinicalService?, units: Int): List<ServiceSupply> = fromDefaults(service?.defaultProducts, units)

    /** Lo que descuenta quitar el renglón: cada insumo de la lista con sus piezas (los que tengan alguna). */
    fun unchargeAll(list: List<ServiceSupply>?): List<Pieces> =
        list.orEmpty().mapIndexedNotNull { index, entry -> if (entry.quantity > 0) Pieces(entry, null, entry.quantity, index) else null }

    /** "Gasa Estéril 10x10 cm ×1 · Jeringa ×2": los insumos con piezas, en el orden de la lista. */
    fun summary(list: List<ServiceSupply>?): String = list.orEmpty().filter { it.quantity > 0 }.joinToString(" · ") { "${it.supplyName} ×${it.quantity}" }
}
