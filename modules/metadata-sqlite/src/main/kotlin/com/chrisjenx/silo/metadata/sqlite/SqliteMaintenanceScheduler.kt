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
package com.chrisjenx.silo.metadata.sqlite

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration

/**
 * Periodic SQLite maintenance: rolls the WAL back into the main DB on every
 * tick and runs a (less frequent) `VACUUM` to reclaim free pages.
 *
 * Without periodic `wal_checkpoint(TRUNCATE)` the `-wal` file grows unbounded
 * under sustained writes (auto-checkpoint only ever appends/rewinds); without
 * an occasional `VACUUM` the main DB never shrinks after large evictions.
 * Both run on the index's writer lock, so they serialize with normal writes
 * instead of blocking reads.
 */
class SqliteMaintenanceScheduler(
    private val index: SqliteMetadataIndex,
    private val checkpointInterval: Duration,
    private val vacuumInterval: Duration,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(SqliteMaintenanceScheduler::class.java)

    /**
     * Launches the maintenance loop in [scope]. Cancelling the scope (e.g. at
     * server shutdown) stops it. Returns the [Job] for explicit cancellation.
     */
    fun launchIn(scope: CoroutineScope): Job =
        scope.launch {
            var lastVacuumMs = clock.millis()
            while (isActive) {
                delay(checkpointInterval.toMillis())
                runCatching { index.walCheckpointTruncate() }
                    .onFailure { log.warn("WAL checkpoint failed", it) }
                if (clock.millis() - lastVacuumMs >= vacuumInterval.toMillis()) {
                    runCatching { index.vacuum() }
                        .onFailure { log.warn("VACUUM failed", it) }
                    lastVacuumMs = clock.millis()
                }
            }
        }
}
