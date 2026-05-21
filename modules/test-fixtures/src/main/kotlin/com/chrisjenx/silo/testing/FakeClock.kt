/*
 * Copyright (c) 2026 Chris Jenkins
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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

    fun set(instant: Instant) {
        now.set(instant)
    }

    fun advance(millis: Long) {
        now.updateAndGet { it.plusMillis(millis) }
    }
}
