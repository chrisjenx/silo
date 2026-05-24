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
package com.chrisjenx.silo.storage.fs

import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.atomic.AtomicLong

/**
 * One-shot boot-time recovery: deletes orphan `tmp.*` files left behind by a
 * crashed PUT (process killed between streaming and the atomic rename). Only
 * files older than [staleTmpAgeMs] are removed, so a PUT in flight from another
 * (concurrent) starting process is never clobbered.
 *
 * Runs synchronously during startup before the server accepts traffic, so it
 * is plain blocking I/O — no coroutine needed. The lifetime [orphansCleaned]
 * count is exposed for the `silo_recovery_orphans_cleaned_total` metric.
 */
class StartupRecovery(
    private val root: Path,
    private val staleTmpAgeMs: Long = DEFAULT_STALE_TMP_AGE_MS,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(StartupRecovery::class.java)
    private val cleaned = AtomicLong(0)

    /** Lifetime count of orphan tmp files removed by recovery scans. */
    val orphansCleaned: Long get() = cleaned.get()

    /** Sweeps the `cas` tree, deleting stale `tmp.` files. Returns how many were removed. */
    fun cleanOrphanTmp(): Int {
        val cas = root.resolve("cas").toFile()
        if (!cas.exists()) return 0
        val cutoffMs = clock.millis() - staleTmpAgeMs
        val stale =
            cas.walkTopDown()
                .filter { it.isFile && it.name.startsWith("tmp.") && it.lastModified() <= cutoffMs }
                .toList()
        var removed = 0
        for (tmp in stale) {
            if (tmp.delete()) removed += 1
        }
        if (removed > 0) {
            cleaned.addAndGet(removed.toLong())
            log.info("startup recovery: removed {} orphan tmp file(s)", removed)
        }
        return removed
    }

    companion object {
        const val DEFAULT_STALE_TMP_AGE_MS: Long = 10 * 60 * 1_000
    }
}
