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
import ch.qos.logback.core.spi.FilterReply
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.slf4j.LoggerFactory

private val accessLogger = LoggerFactory.getLogger("io.ktor.server.plugins.calllogging.CallLogging") as Logger
private val otherLogger = LoggerFactory.getLogger("com.chrisjenx.silo.other") as Logger

private fun SamplingTurboFilter.decideInfo(logger: Logger = accessLogger) = decide(null, logger, Level.INFO, "msg", null, null)

class SamplingTurboFilterSpec : StringSpec({

    "rate <= 1 never samples" {
        val filter = SamplingTurboFilter().apply { rate = 1 }
        repeat(5) { filter.decideInfo() shouldBe FilterReply.NEUTRAL }
    }

    "rate N keeps 1 of every N matching INFO events" {
        val filter = SamplingTurboFilter().apply { rate = 3 }
        filter.decideInfo() shouldBe FilterReply.DENY
        filter.decideInfo() shouldBe FilterReply.DENY
        filter.decideInfo() shouldBe FilterReply.NEUTRAL
        filter.decideInfo() shouldBe FilterReply.DENY
    }

    "WARN and above are never dropped" {
        val filter = SamplingTurboFilter().apply { rate = 2 }
        filter.decide(null, accessLogger, Level.WARN, "m", null, null) shouldBe FilterReply.NEUTRAL
        filter.decide(null, accessLogger, Level.ERROR, "m", null, null) shouldBe FilterReply.NEUTRAL
    }

    "non-matching loggers pass through untouched" {
        val filter = SamplingTurboFilter().apply { rate = 2 }
        repeat(5) { filter.decideInfo(otherLogger) shouldBe FilterReply.NEUTRAL }
    }
})
