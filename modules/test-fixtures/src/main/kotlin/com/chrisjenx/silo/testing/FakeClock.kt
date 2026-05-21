package com.chrisjenx.silo.testing

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference

/**
 * A deterministic, mutable [Clock] for time-sensitive tests (TTL sweep,
 * last-access batching, eviction ordering). Mutations are atomic.
 */
class FakeClock(
    initial: Instant = Instant.parse("2026-01-01T00:00:00Z"),
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {

    private val now = AtomicReference(initial)

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = FakeClock(now.get(), zone)

    override fun instant(): Instant = now.get()

    fun set(instant: Instant) { now.set(instant) }

    fun advance(millis: Long) {
        now.updateAndGet { it.plusMillis(millis) }
    }
}
