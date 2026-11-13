package io.github.marioalr.tpvca.domain.business

import io.github.marioalr.tpvca.domain.patients.DuplicatePatientRules
import java.util.Locale

/**
 * Catálogo repetido entre equipos (`Services/Rules/CatalogDuplicateRules.cs`; decisión del médico del 2026-10-22: la tablet también da de alta productos, servicios y categorías). Cada registro es su
 * `SyncId` con LWW por fila: dos equipos que dan de alta «lo mismo» sin verse dejan dos filas. Esta regla solo las encuentra para avisar: **nunca une, borra ni renombra nada**.
 * Vectores: `contracts/golden/duplicate-catalog.json`.
 *
 * - **Mismo código** (productos y servicios): sin espacios a los lados y en mayúsculas invariantes; un código vacío no se compara.
 * - **Mismo nombre**: normalizado como `duplicate-patients.json`; en un producto, con su presentación ("nombre | presentación").
 * - Solo los activos con nombre, del mismo tipo, y nunca el mismo `SyncId`.
 */
object CatalogDuplicateRules {
    /** Qué parte del catálogo: `Products`, `ClinicalServices` o `Categories`, en este orden. */
    enum class Kind { Product, Service, Category }

    /** Por qué se parecen dos registros (los nombres son los de la PC). */
    enum class Match { None, Code, Name, CodeAndName }

    /** Un registro como lo ve la regla. [syncId] en minúsculas. */
    data class Item(val kind: Kind, val syncId: String, val code: String?, val name: String?, val presentation: String? = null, val isActive: Boolean = true)

    /** Dos registros que pueden ser el mismo: [first] es el de `SyncId` menor. */
    data class Duplicate(val first: Item, val second: Item, val match: Match)

    /** El código para comparar, o `null` si está vacío o es una categoría. */
    fun codeKey(item: Item): String? {
        if (item.kind == Kind.Category) return null
        val code = item.code.orEmpty().trim().uppercase(Locale.ROOT)
        return code.ifEmpty { null }
    }

    /** El nombre para comparar: normalizado, y en un producto con " | " y su presentación (si la trae). */
    fun nameKey(item: Item): String {
        val name = DuplicatePatientRules.normalizedName(item.name)
        if (name.isEmpty() || item.kind != Kind.Product) return name
        val presentation = DuplicatePatientRules.normalizedName(item.presentation)
        return if (presentation.isEmpty()) name else "$name | $presentation"
    }

    /** Activo y con nombre. */
    fun counts(item: Item): Boolean = item.isActive && DuplicatePatientRules.normalizedName(item.name).isNotEmpty()

    fun compare(a: Item, b: Item): Match {
        if (a.kind != b.kind || (a.syncId.isNotEmpty() && a.syncId.equals(b.syncId, ignoreCase = true)) || !counts(a) || !counts(b)) return Match.None
        val code = codeKey(a)?.let { it == codeKey(b) } == true
        val name = nameKey(a) == nameKey(b)
        return when {
            code && name -> Match.CodeAndName
            code -> Match.Code
            name -> Match.Name
            else -> Match.None
        }
    }

    /**
     * Los pares en que está alguno de [involving] (lo que acaba de bajar): cada par una vez, el primero el de `SyncId` menor, ordenados por tipo y luego por `SyncId` (ordinal). Solo se comparan los que
     * comparten código o nombre.
     */
    fun pairs(items: Iterable<Item>, involving: Set<String>): List<Duplicate> {
        val wanted = involving.map { it.lowercase(Locale.ROOT) }.toSet()
        val result = ArrayList<Duplicate>()
        for ((_, group) in items.filter(::counts).groupBy { it.kind }.toSortedMap()) {
            val list = group.sortedBy { it.syncId.lowercase(Locale.ROOT) }
            val buckets = HashMap<String, MutableList<Int>>()
            list.forEachIndexed { index, item ->
                codeKey(item)?.let { buckets.getOrPut("c:$it") { ArrayList() } += index }
                buckets.getOrPut("n:" + nameKey(item)) { ArrayList() } += index
            }
            val seen = HashSet<Long>()
            for (indexes in buckets.values) {
                if (indexes.size < 2) continue
                val sorted = indexes.distinct().sorted()
                for (i in sorted.indices) {
                    for (j in i + 1 until sorted.size) {
                        val a = list[sorted[i]]
                        val b = list[sorted[j]]
                        if (a.syncId.lowercase(Locale.ROOT) !in wanted && b.syncId.lowercase(Locale.ROOT) !in wanted) continue
                        if (!seen.add(sorted[i].toLong() * list.size + sorted[j])) continue
                        val match = compare(a, b)
                        if (match != Match.None) result += Duplicate(a, b, match)
                    }
                }
            }
        }
        return result.sortedWith(compareBy<Duplicate> { it.first.kind }.thenBy { it.first.syncId.lowercase(Locale.ROOT) }.thenBy { it.second.syncId.lowercase(Locale.ROOT) })
    }

    /** "mismo código", "mismo nombre" o las dos. */
    fun describe(match: Match): String = when (match) {
        Match.CodeAndName -> "mismo código y mismo nombre"
        Match.Code -> "mismo código"
        Match.Name -> "mismo nombre"
        Match.None -> ""
    }

    /** "producto", "servicio" o "categoría". */
    fun kindText(kind: Kind): String = when (kind) {
        Kind.Product -> "producto"
        Kind.Service -> "servicio"
        Kind.Category -> "categoría"
    }

    /** La palabra de la llave del aviso (sin acento): `catalogo:<tipo>:<SyncId menor>:<SyncId mayor>`. */
    fun kindKey(kind: Kind): String = when (kind) {
        Kind.Product -> "producto"
        Kind.Service -> "servicio"
        Kind.Category -> "categoria"
    }

    fun kindOfKey(key: String): Kind? = Kind.entries.firstOrNull { kindKey(it) == key }

    /** La llave del aviso (`SyncNotices.CatalogKey`). */
    fun noticeKey(kind: Kind, a: String, b: String): String {
        val (low, high) = listOf(a.lowercase(Locale.ROOT), b.lowercase(Locale.ROOT)).sorted()
        return "catalogo:${kindKey(kind)}:$low:$high"
    }

    /** "producto «Paracetamol» (MED-010) y «Paracetamol» (MED-011), mismo nombre" (`DuplicateCatalogPair.Text`). */
    fun pairText(pair: Duplicate): String = "${kindText(pair.first.kind)} ${describeItem(pair.first)} y ${describeItem(pair.second)}, ${describe(pair.match)}"

    private fun describeItem(item: Item): String {
        val presentation = item.presentation
        val name = if (presentation.isNullOrBlank()) "«${item.name?.trim()}»" else "«${item.name?.trim()} (${presentation.trim()})»"
        val code = item.code
        return if (code.isNullOrBlank()) name else "$name (${code.trim()})"
    }

    /** Cuántos pares se nombran en el aviso; los demás se cuentan (`SyncArrivals.MaxCatalogPairsShown`). */
    const val MAX_PAIRS_SHOWN = 3

    /** El aviso de arriba de Productos (`SyncArrivals.DuplicateCatalogNotice`); vacío sin pares. */
    fun notice(pairs: List<Duplicate>): String {
        if (pairs.isEmpty()) return ""
        val head = if (pairs.size == 1) "Posible registro repetido en el catálogo" else "Posibles registros repetidos en el catálogo"
        val more = if (pairs.size > MAX_PAIRS_SHOWN) " y ${pairs.size - MAX_PAIRS_SHOWN} más" else ""
        return "$head con lo que llegó de otro equipo: " + pairs.take(MAX_PAIRS_SHOWN).joinToString("; ") { pairText(it) } + more +
            ". Si son lo mismo, desactive uno (sus ventas y lotes se conservan) o cámbiele el código. TPVC no los une solo."
    }
}
