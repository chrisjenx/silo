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
package com.chrisjenx.silo.server

import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class SiloConfigSpec : StringSpec({

    "load applies the documented capacity + eviction defaults" {
        val c = SiloConfig.load(ConfigFactory.empty())
        c.maxBytes shouldBe 107_374_182_400L
        c.maxEntries shouldBe 1_000_000L
        c.reservedFreeBytes shouldBe 5_368_709_120L
        c.reservedFreeInodes shouldBe 100_000L
        c.maxAgeDays shouldBe 30
        c.maxDeletesPerCycle shouldBe 1_000
    }

    "load reads capacity + eviction overrides from HOCON" {
        val c =
            SiloConfig.load(
                ConfigFactory.parseString(
                    """
                    silo.storage.max-bytes = 5
                    silo.storage.max-entries = 6
                    silo.storage.reserved-free-bytes = 7
                    silo.storage.reserved-free-inodes = 8
                    silo.eviction.max-age-days = 9
                    silo.eviction.max-deletes-per-cycle = 10
                    """.trimIndent(),
                ),
            )
        c.maxBytes shouldBe 5L
        c.maxEntries shouldBe 6L
        c.reservedFreeBytes shouldBe 7L
        c.reservedFreeInodes shouldBe 8L
        c.maxAgeDays shouldBe 9
        c.maxDeletesPerCycle shouldBe 10
    }

    "load applies the eviction sweep-interval default and override" {
        SiloConfig.load(com.typesafe.config.ConfigFactory.empty())
            .evictionSweepIntervalSeconds shouldBe 60L
        SiloConfig.load(
            com.typesafe.config.ConfigFactory.parseString(
                "silo.eviction.sweep-interval-seconds = 5",
            ),
        ).evictionSweepIntervalSeconds shouldBe 5L
    }
})
