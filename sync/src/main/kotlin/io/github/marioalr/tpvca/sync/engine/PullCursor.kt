package io.github.marioalr.tpvca.sync.engine

/**
 * El cursor de una tabla: `(SyncedAtUtc, SyncId)` de lo último aplicado, guardado como `SyncedAtUtc|SyncId` y sin interpretar. La hora
 * es de la nube y se compara con la misma regla que SQLite: como texto.
 */
data class PullCursor(val syncedAtUtc: String, val key: String) : Comparable<PullCursor> {
    override fun compareTo(other: PullCursor): Int =
        compareValuesBy(this, other, PullCursor::syncedAtUtc, PullCursor::key)

    fun serialize(): String = "$syncedAtUtc|$key"

    companion object {
        val START = PullCursor("", "")

        fun parse(text: String?): PullCursor? {
            val bar = text?.indexOf('|') ?: return null
            return if (bar < 0) null else PullCursor(text.substring(0, bar), text.substring(bar + 1))
        }
    }
}
