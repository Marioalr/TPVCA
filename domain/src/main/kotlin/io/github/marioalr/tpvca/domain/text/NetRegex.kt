package io.github.marioalr.tpvca.domain.text

import java.util.regex.PatternSyntaxException

/**
 * Expresiones con `\w`, `\d`, `\s` y `\b` de Unicode, como .NET. En el JDK eso es la bandera `(?U)`; **en Android no existe**: ahí manda ICU, que ya es Unicode, y su compilador
 * rechaza `(?U)` con `PatternSyntaxException` (lo descubrió K4 en el emulador: en la JVM las pruebas pasan). Por eso ninguna regla escribe `(?U)` a mano. `DomainRegexDeviceTest` las
 * compila en un equipo. [flags] son las demás banderas en línea ("i", "d").
 */
object NetRegex {
    private val JDK_NEEDS_FLAG: Boolean = try {
        Regex("(?U)x")
        true
    } catch (_: PatternSyntaxException) {
        false
    }

    fun of(pattern: String, flags: String = "", options: Set<RegexOption> = emptySet()): Regex {
        val all = flags + if (JDK_NEEDS_FLAG) "U" else ""
        return Regex((if (all.isEmpty()) "" else "(?$all)") + pattern, options)
    }
}
