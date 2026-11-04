package io.github.marioalr.tpvca.domain.search

import io.github.marioalr.tpvca.domain.model.Product
import io.github.marioalr.tpvca.domain.text.MexicanText
import java.text.Normalizer

/**
 * Búsqueda sin acentos, igual a `Services/TextSearch.cs` de la PC. Vectores: `contracts/golden/text-search.json`.
 *
 * [rankProducts] (el Punto de venta y los insumos de un servicio) con `rank-products.json`.
 */
object TextSearch {
    /**
     * Quita los espacios de los lados, descompone en NFD, quita las marcas que no ocupan espacio (categoría Mn: acentos,
     * diéresis y la tilde de la ñ, así que ñ queda n), pasa cada carácter a minúscula invariante y recompone en NFC.
     * Sin texto (nulo, vacío o solo espacios) da "".
     */
    fun normalize(text: String?): String {
        if (text.isNullOrBlank()) return ""
        val decomposed = Normalizer.normalize(text.trim(), Normalizer.Form.NFD)
        val sb = StringBuilder(decomposed.length)
        for (c in decomposed) {
            if (Character.getType(c) != Character.NON_SPACING_MARK.toInt()) {
                // char.ToLowerInvariant de .NET: carácter por carácter, sin reglas del idioma
                sb.append(c.lowercaseChar())
            }
        }
        return Normalizer.normalize(sb, Normalizer.Form.NFC)
    }

    /** `true` si alguno de los campos contiene la consulta, ya normalizada con [normalize] (comparación ordinal). */
    fun matches(normalizedQuery: String, vararg fields: String?): Boolean {
        if (normalizedQuery.isEmpty()) return true
        return fields.any { !it.isNullOrEmpty() && normalize(it).contains(normalizedQuery) }
    }

    /**
     * Índice de la mejor opción para "escribir y saltar" en una lista: primero las que empiezan con lo escrito, luego las que
     * tienen una palabra que empieza así y al final las que lo contienen. -1 si ninguna coincide o no hay texto.
     */
    fun findBestMatchIndex(options: Iterable<String?>, typed: String?): Int {
        val q = normalize(typed)
        if (q.isEmpty()) return -1

        var wordMatch = -1
        var containsMatch = -1
        for ((i, option) in options.withIndex()) {
            val text = normalize(option)
            if (text.startsWith(q)) return i
            if (wordMatch < 0 && " $text".contains(" $q")) wordMatch = i
            if (containsMatch < 0 && text.contains(q)) containsMatch = i
        }
        return if (wordMatch >= 0) wordMatch else containsMatch
    }

    /** Cuántas sugerencias da [rankProducts] si no se pide otra cosa. */
    const val PRODUCT_SUGGESTION_LIMIT = 8

    /**
     * Productos que coinciden (sin acentos): grupo 0 el código normalizado igual, 1 el nombre que empieza así, 2 el genérico que
     * empieza así, 3 una palabra del nombre o del genérico que empieza así y 4 lo que lo contiene en nombre, genérico, código o
     * ubicación. Orden: grupo, los que tienen existencias antes y el nombre con `Collator` es-MX; [limit] como mucho. No mira si
     * el producto está activo. Sin texto, ninguno.
     */
    fun rankProducts(products: Iterable<Product>, query: String?, limit: Int = PRODUCT_SUGGESTION_LIMIT): List<Product> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        val names = MexicanText.nameOrder()
        return products
            .mapNotNull { p ->
                val name = normalize(p.name)
                val generic = normalize(p.genericName)
                val code = normalize(p.code)
                val rank = when {
                    code == q -> 0
                    name.startsWith(q) -> 1
                    generic.startsWith(q) -> 2
                    " $name".contains(" $q") || " $generic".contains(" $q") -> 3
                    matches(q, p.name, p.genericName, p.code, p.location) -> 4
                    else -> -1
                }
                if (rank >= 0) p to rank else null
            }
            .sortedWith(
                compareBy<Pair<Product, Int>> { it.second }
                    .thenBy { it.first.currentStock <= 0 }
                    .thenComparing({ it.first.name }, names),
            )
            .take(limit)
            .map { it.first }
    }
}
