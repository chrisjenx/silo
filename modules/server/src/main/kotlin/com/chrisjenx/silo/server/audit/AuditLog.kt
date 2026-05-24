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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * One admin-API mutation worth recording. Rendered to a single JSON line
 * (JSONL) — see [toJsonLine].
 */
data class AuditEntry(
    val timestampMs: Long,
    val actor: String,
    val action: String,
    val outcome: String,
    val details: Map<String, String> = emptyMap(),
) {
    /** Single-line JSON; string values are escaped so newlines/quotes can't break the line. */
    fun toJsonLine(): String {
        val ts = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(timestampMs))
        val sb = StringBuilder("{")
        sb.append("\"timestamp\":\"").append(ts).append("\",")
        sb.append("\"actor\":").append(jsonString(actor)).append(',')
        sb.append("\"action\":").append(jsonString(action)).append(',')
        sb.append("\"outcome\":").append(jsonString(outcome))
        if (details.isNotEmpty()) {
            sb.append(",\"details\":{")
            details.entries.forEachIndexed { i, (k, v) ->
                if (i > 0) sb.append(',')
                sb.append(jsonString(k)).append(':').append(jsonString(v))
            }
            sb.append('}')
        }
        return sb.append('}').toString()
    }
}

/** Records admin mutations. Implementations must be safe for concurrent calls. */
fun interface AuditLog {
    suspend fun record(entry: AuditEntry)
}

/** Audit disabled — drops every entry. */
object NoopAuditLog : AuditLog {
    override suspend fun record(entry: AuditEntry) = Unit
}

/**
 * Append-only JSONL audit log with daily rotation. Each [record] appends one
 * line to `audit-<UTC-date>.jsonl` under [dir]; the date is derived per write,
 * so rotation happens automatically at the UTC day boundary with no scheduler.
 *
 * Appends rely on `O_APPEND` atomicity for small lines rather than a shared
 * lock, so we never hold a mutex across I/O.
 */
class JsonlAuditLog(
    private val dir: Path,
    private val clock: Clock = Clock.systemUTC(),
) : AuditLog {
    override suspend fun record(entry: AuditEntry): Unit =
        withContext(Dispatchers.IO) {
            Files.createDirectories(dir)
            val file = dir.resolve("audit-${today()}.jsonl")
            Files.write(
                file,
                (entry.toJsonLine() + "\n").toByteArray(Charsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND,
            )
        }

    private fun today(): String = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).toString()
}

private fun jsonString(value: String): String {
    val sb = StringBuilder("\"")
    for (c in value) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    return sb.append('"').toString()
}
