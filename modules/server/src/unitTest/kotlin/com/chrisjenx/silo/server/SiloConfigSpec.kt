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

    // Guards the env-var -> HOCON-key wiring in the bundled application.conf:
    // every documented SILO_* override must land on the key SiloConfig reads.
    // A typo'd ${?SILO_*} path would silently no-op (the bug this fixes).
    "bundled application.conf wires the documented env-var overrides to the right keys" {
        val overrides =
            ConfigFactory.parseString(
                """
                SILO_STORAGE_ROOT = "/srv/silo-x"
                SILO_MAX_BYTES = 11
                SILO_MAX_ENTRIES = 12
                SILO_MAX_ENTRY_BYTES = 16
                SILO_RESERVED_FREE_BYTES = 13
                SILO_RESERVED_FREE_INODES = 14
                SILO_MAX_AGE_DAYS = 15
                SILO_ANONYMOUS_READ = false
                SILO_USERS_FILE = "/etc/silo/users.conf"
                """.trimIndent(),
            )
        val resolved =
            overrides.withFallback(ConfigFactory.parseResources("application.conf")).resolve()
        val c = SiloConfig.load(resolved)
        c.storageRoot.toString() shouldBe "/srv/silo-x"
        c.maxBytes shouldBe 11L
        c.maxEntries shouldBe 12L
        c.maxEntryBytes shouldBe 16L
        c.reservedFreeBytes shouldBe 13L
        c.reservedFreeInodes shouldBe 14L
        c.maxAgeDays shouldBe 15
        c.anonymousRead shouldBe false
        c.usersConfPath?.toString() shouldBe "/etc/silo/users.conf"
    }
})
