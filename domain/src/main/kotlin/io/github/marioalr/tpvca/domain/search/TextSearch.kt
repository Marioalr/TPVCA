package io.github.marioalr.tpvca.domain.search

import java.text.Normalizer

/**
 * Búsqueda sin acentos, igual a `Services/TextSearch.cs` de la PC. Vectores: `contracts/golden/text-search.json`.
 *
 * Por ahora solo [normalize], [matches] y [findBestMatchIndex]; `RankProducts` llega con su vector (`rank-products.json`).
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
}
