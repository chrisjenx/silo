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
package com.chrisjenx.silo.server.audit

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class AuditLogSpec : StringSpec({

    "toJsonLine renders a single line with all fields" {
        val line =
            AuditEntry(
                timestampMs = Instant.parse("2026-05-24T10:00:00Z").toEpochMilli(),
                actor = "alice",
                action = "storage.reconcile",
                outcome = "ok",
                details = mapOf("orphanRowsDeleted" to "3"),
            ).toJsonLine()
        line shouldNotContain "\n"
        line shouldContain "\"timestamp\":\"2026-05-24T10:00:00Z\""
        line shouldContain "\"actor\":\"alice\""
        line shouldContain "\"action\":\"storage.reconcile\""
        line shouldContain "\"outcome\":\"ok\""
        line shouldContain "\"orphanRowsDeleted\":\"3\""
    }

    "toJsonLine escapes quotes and newlines so the line is never broken" {
        val line =
            AuditEntry(
                timestampMs = 0L,
                actor = "ev\"il\nname",
                action = "x",
                outcome = "ok",
            ).toJsonLine()
        line shouldNotContain "\n"
        line shouldContain "ev\\\"il\\nname"
    }

    "JsonlAuditLog appends to a UTC-dated file and creates the directory" {
        val dir = Files.createTempDirectory("silo-audit-")
        try {
            val clock = Clock.fixed(Instant.parse("2026-05-24T10:00:00Z"), ZoneOffset.UTC)
            val log = JsonlAuditLog(dir, clock)
            log.record(AuditEntry(0L, "a", "act1", "ok"))
            log.record(AuditEntry(0L, "b", "act2", "ok"))

            val file = dir.resolve("audit-2026-05-24.jsonl")
            Files.exists(file) shouldBe true
            val lines = Files.readAllLines(file)
            lines shouldHaveSize 2
            lines[0] shouldContain "\"action\":\"act1\""
            lines[1] shouldContain "\"action\":\"act2\""
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
})
