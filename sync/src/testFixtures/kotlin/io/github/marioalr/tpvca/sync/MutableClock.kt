package io.github.marioalr.tpvca.sync

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Un reloj que las pruebas mueven a mano. */
class MutableClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = now

    fun advance(by: Duration) {
        now = now.plus(by)
    }
}
