package io.github.marioalr.tpvca.sync.hrana

/**
 * Quita de un texto lo que nunca debe salir en un mensaje o una bitácora: el token y la URL de la base. Los busca literalmente y, por si
 * vienen de otro lado (el cuerpo de un error de Turso, el nombre del host en un error de la red), también por su forma.
 */
object Redactor {
    private const val HIDDEN = "[oculto]"

    private val BEARER = Regex("""(?i)\bBearer\s+\S+""")
    private val JWT = Regex("""\beyJ[A-Za-z0-9_-]+(\.[A-Za-z0-9_-]*){0,2}""")
    private val URL = Regex("""(?i)\b(libsql|turso|https?|wss?)://[^\s"'<>)]+""")
    private val TURSO_HOST = Regex("""(?i)\b[a-z0-9-]+(\.[a-z0-9-]+)*\.turso\.io\b""")

    fun clean(text: String, secrets: List<String> = emptyList()): String {
        var clean = text
        // Los más largos primero: la URL del pipeline contiene a la URL pegada
        for (secret in secrets.filter { it.isNotBlank() }.sortedByDescending { it.length }) clean = clean.replace(secret, HIDDEN)
        for (pattern in listOf(BEARER, JWT, URL, TURSO_HOST)) clean = pattern.replace(clean, HIDDEN)
        return clean
    }
}
