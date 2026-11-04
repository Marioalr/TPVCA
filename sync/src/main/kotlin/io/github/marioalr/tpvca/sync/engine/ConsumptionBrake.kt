package io.github.marioalr.tpvca.sync.engine

import io.github.marioalr.tpvca.domain.Clinic
import java.time.Clock

/**
 * El freno de consumo (paso 8.2), como en la PC: como máximo 2 descargas completas al día y un tope diario estimado de lecturas y
 * escrituras. Los contadores siguen el día del consultorio y sobreviven a un cambio de generación: un ciclo que vacíe la base una y
 * otra vez es justo lo que el freno detiene.
 */
class ConsumptionBrake(private val store: SyncStore, private val clock: Clock) {
    val isTripped: Boolean get() = store.meta(TRIPPED) != null

    val fullPullsToday: Int get() = current(FULL_PULLS)
    val rowsReadToday: Long get() = currentLong(ROWS_READ)
    val rowsWrittenToday: Long get() = currentLong(ROWS_WRITTEN)

    /** ¿Se puede empezar otra descarga completa? Si no, activa el freno. Con el freno activo tampoco, hasta *Reanudar*. */
    fun allowFullPull(): Boolean {
        if (isTripped || fullPullsToday >= MAX_FULL_PULLS_PER_DAY) {
            trip()
            return false
        }
        return true
    }

    fun recordFullPull() = save(FULL_PULLS to (fullPullsToday + 1).toLong())

    /** Suma lo de hoy y activa el freno si pasó un tope. */
    fun recordUsage(rowsRead: Long, rowsWritten: Long = 0) {
        val read = rowsReadToday + rowsRead
        val written = rowsWrittenToday + rowsWritten
        save(ROWS_READ to read, ROWS_WRITTEN to written)
        if (read > MAX_ROWS_READ_PER_DAY || written > MAX_ROWS_WRITTEN_PER_DAY) trip()
    }

    /** *Reanudar*: el día se vuelve a contar desde cero, así el freno solo regresa si el consumo anormal sigue. */
    fun resume() = store.apply(StoreChanges(metaRemoved = setOf(TRIPPED, DAY, FULL_PULLS, ROWS_READ, ROWS_WRITTEN)))

    private fun trip() {
        if (!isTripped) store.apply(StoreChanges(meta = mapOf(TRIPPED to clock.instant().toString())))
    }

    private fun today() = Clinic.today(clock.instant()).toString()

    private fun current(key: String) = currentLong(key).toInt()

    private fun currentLong(key: String): Long =
        if (store.meta(DAY) == today()) store.meta(key)?.toLongOrNull() ?: 0L else 0L

    private fun save(vararg values: Pair<String, Long>) {
        val day = today()
        val rolled = if (store.meta(DAY) == day) emptySet() else setOf(FULL_PULLS, ROWS_READ, ROWS_WRITTEN)
        store.apply(
            StoreChanges(
                meta = mapOf(DAY to day) + values.associate { it.first to it.second.toString() },
                metaRemoved = rolled - values.map { it.first }.toSet(),
            ),
        )
    }

    companion object {
        const val MAX_FULL_PULLS_PER_DAY = 2
        const val MAX_ROWS_READ_PER_DAY = 3_000_000L
        const val MAX_ROWS_WRITTEN_PER_DAY = 30_000L

        /** Lo que sobrevive a vaciar la base por un cambio de generación. */
        const val META_PREFIX = "Usage:"
        private const val DAY = "${META_PREFIX}Day"
        private const val FULL_PULLS = "${META_PREFIX}FullPulls"
        private const val ROWS_READ = "${META_PREFIX}RowsRead"
        private const val ROWS_WRITTEN = "${META_PREFIX}RowsWritten"
        private const val TRIPPED = "${META_PREFIX}BrakeTrippedUtc"
    }
}
