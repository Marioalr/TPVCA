package io.github.marioalr.tpvca.domain.patients

/**
 * La fecha dd/mm/aaaa con las diagonales solas (`Helpers/DateMaskText.cs`): qué queda en el cuadro y dónde queda el cursor al escribir, pegar o borrar. El texto siempre queda como día/mes/año: hasta 2, 2 y 4
 * dígitos con sus diagonales. Vectores: `golden/date-mask.json`.
 */
object DateMaskText {
    const val MAX_LENGTH = 10

    data class Edit(val text: String, val caret: Int)

    private val ISO_DATE = Regex("^([0-9]{4})[-/.]([0-9]{1,2})[-/.]([0-9]{1,2})$")

    /** Escribir (o pegar) [input] sobre la selección. `null` si no entra nada. */
    fun insert(text: String?, selectionStart: Int, selectionLength: Int, input: String?): Edit? {
        val current = text.orEmpty()
        val (start, length) = clamp(current, selectionStart, selectionLength)
        val typed = buildString {
            for (c in input.orEmpty()) {
                if (c in '0'..'9') append(c) else if (isSeparator(c)) append('/')
            }
        }
        if (typed.isEmpty()) return null

        val before = current.substring(0, start)
        val after = current.substring(start + length)
        if (typed == "/") {
            // La diagonal ya está ahí (la puso la máscara): solo se pasa sobre ella
            if (length == 0 && after.isNotEmpty() && isSeparator(after[0])) return Edit(current, start + 1)
            // Sin día o mes antes no hay nada que cerrar
            if (before.isEmpty() || isSeparator(before.last())) return null
        }

        val edit = normalize(before + typed + after, before.length + typed.length, typing = true) ?: return null
        // Día o mes completo justo antes de su diagonal: el cursor pasa a la parte siguiente
        return if (edit.caret < edit.text.length && edit.text[edit.caret] == '/' && digitsBefore(edit.text, edit.caret) == 2) edit.copy(caret = edit.caret + 1) else edit
    }

    /** Pegar: como escribir todo de una vez. Una fecha año-mes-día se acomoda a día/mes/año. `null` si no cabe. */
    fun paste(text: String?, selectionStart: Int, selectionLength: Int, pasted: String?): Edit? {
        var clean = pasted?.trim().orEmpty()
        ISO_DATE.find(clean)?.let { clean = "${it.groupValues[3]}/${it.groupValues[2]}/${it.groupValues[1]}" }
        return insert(text, selectionStart, selectionLength, clean)
    }

    /** Retroceso. Justo después de una diagonal: si es la última del texto se borra con el dígito anterior; a media fecha se borra el dígito anterior y la diagonal se queda. */
    fun backspace(text: String?, selectionStart: Int, selectionLength: Int): Edit? {
        val current = text.orEmpty()
        val (start, length) = clamp(current, selectionStart, selectionLength)
        if (length > 0) return remove(current, start, length)
        if (start == 0) return null
        if (current[start - 1] == '/' && start >= 2 && current[start - 2] in '0'..'9') {
            return if (start == current.length) remove(current, start - 2, 2) else remove(current, start - 2, 1)
        }
        return remove(current, start - 1, 1)
    }

    /** Suprimir. Justo antes de una diagonal se borra el dígito que la sigue y la diagonal se queda; si no la sigue un dígito se borra la diagonal. */
    fun delete(text: String?, selectionStart: Int, selectionLength: Int): Edit? {
        val current = text.orEmpty()
        val (start, length) = clamp(current, selectionStart, selectionLength)
        if (length > 0) return remove(current, start, length)
        if (start >= current.length) return null
        if (current[start] == '/' && start + 1 < current.length && current[start + 1] in '0'..'9') return remove(current, start + 1, 1)
        return remove(current, start, 1)
    }

    private fun remove(text: String, start: Int, length: Int): Edit = normalize(text.removeRange(start, start + length), start, typing = false)!!

    private fun normalize(raw: String, caret: Int, typing: Boolean): Edit? {
        val lastSeparator = raw.indexOfLast(::isSeparator)
        val out = StringBuilder(MAX_LENGTH)
        var part = 0
        var partDigits = 0
        var mappedCaret = 0
        for (i in 0..raw.length) {
            if (i == caret) mappedCaret = out.length
            if (i == raw.length) break
            val c = raw[i]
            if (isSeparator(c)) {
                if (part == 2) {
                    if (typing) return null
                    continue
                }
                out.append('/')
                part++
                partDigits = 0
            } else if (c in '0'..'9') {
                if (partDigits == (if (part < 2) 2 else 4)) {
                    if (part == 2 || (typing && i < lastSeparator)) {
                        if (typing) return null
                        continue
                    }
                    out.append('/')
                    part++
                    partDigits = 0
                }
                out.append(c)
                partDigits++
            }
            // Lo demás no se queda
        }
        // Día o mes recién completado al final: su diagonal aparece sola
        if (typing && part < 2 && partDigits == 2 && mappedCaret == out.length) out.append('/')
        return Edit(out.toString(), mappedCaret)
    }

    private fun isSeparator(c: Char) = c == '/' || c == '-' || c == '.'

    private fun digitsBefore(text: String, index: Int): Int {
        var count = 0
        var i = index - 1
        while (i >= 0 && text[i] in '0'..'9') {
            count++
            i--
        }
        return count
    }

    private fun clamp(text: String, start: Int, length: Int): Pair<Int, Int> {
        val s = start.coerceIn(0, text.length)
        return s to length.coerceIn(0, text.length - s)
    }

    /**
     * Lo que Android entrega es el texto ya cambiado, no la tecla: compara el texto de antes y el de ahora, y lo convierte en [insert] o [backspace]. Regresa `null` si la tecla no entra (el cuadro sigue como
     * estaba). [oldStart] y [oldLength] son la selección de antes; [newCaret], dónde quedó el cursor del cambio.
     */
    fun applyChange(old: String, oldStart: Int, oldLength: Int, new: String, newCaret: Int): Edit? {
        if (old == new) return null
        val start = oldStart.coerceIn(0, old.length)
        val length = oldLength.coerceIn(0, old.length - start)
        // Lo escrito reemplazó la selección y quedó justo antes del cursor
        val keptAfter = old.length - start - length
        if (length > 0 && new.length >= start + keptAfter && new.substring(0, start) == old.substring(0, start) && new.substring(new.length - keptAfter) == old.substring(old.length - keptAfter)) {
            val typed = new.substring(start, new.length - keptAfter)
            return if (typed.isEmpty()) backspace(old, start, length) else insert(old, start, length, typed)
        }
        var prefix = 0
        while (prefix < old.length && prefix < new.length && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        while (suffix < old.length - prefix && suffix < new.length - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
        val removed = old.length - prefix - suffix
        val added = new.substring(prefix, new.length - suffix)
        // Sin selección, un retroceso de un carácter: el cursor de antes iba después de lo que se quitó
        if (added.isEmpty()) {
            return if (removed == 1 && oldStart in (prefix + 1)..old.length && oldLength == 0) backspace(old, oldStart, 0) else backspace(old, prefix, removed)
        }
        // `newCaret` queda después de lo escrito aunque se repita un dígito ("11"): el texto escrito termina en el cursor
        val insertAt = (newCaret - added.length).coerceIn(0, old.length)
        val at = if (removed == 0 && old.substring(0, insertAt) + added + old.substring(insertAt) == new) insertAt else prefix
        return if (added.length > 1) paste(old, at, removed, added) else insert(old, at, removed, added)
    }
}
