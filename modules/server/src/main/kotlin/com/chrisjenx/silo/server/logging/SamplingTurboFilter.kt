/*
 * Copyright 2026 Silo contributors
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
package com.chrisjenx.silo.server.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.turbo.TurboFilter
import ch.qos.logback.core.spi.FilterReply
import org.slf4j.Marker
import java.util.concurrent.atomic.AtomicLong

/**
 * Logback turbo filter that down-samples high-volume access logs: keeps 1 of
 * every [rate] events from loggers under [loggerName] at or below INFO, and
 * passes everything else through untouched. WARN/ERROR are never dropped.
 *
 * Configured in `logback.xml`; [rate] is wired from `SILO_LOG_SAMPLE_RATE`
 * (1 = log everything, the default). Sampling cuts per-request log volume on
 * busy nodes without losing problem signals.
 */
class SamplingTurboFilter : TurboFilter() {
    /** Keep 1 of every N matching events. `<= 1` disables sampling. */
    var rate: Int = 1

    /** Only events from loggers whose name starts with this are sampled. */
    var loggerName: String = "io.ktor.server.plugins.calllogging"

    private val counter = AtomicLong()

    @Suppress("LongParameterList")
    override fun decide(
        marker: Marker?,
        logger: Logger,
        level: Level,
        format: String?,
        params: Array<out Any?>?,
        t: Throwable?,
    ): FilterReply {
        if (rate <= 1) return FilterReply.NEUTRAL
        if (level.isGreaterOrEqual(Level.WARN)) return FilterReply.NEUTRAL
        if (!logger.name.startsWith(loggerName)) return FilterReply.NEUTRAL
        return if (counter.incrementAndGet() % rate == 0L) FilterReply.NEUTRAL else FilterReply.DENY
    }
}
