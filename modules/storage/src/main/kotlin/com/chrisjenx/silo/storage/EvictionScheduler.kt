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
package com.chrisjenx.silo.storage

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Runs [sweep] on a fixed [interval] until the scope it is launched in is
 * cancelled (e.g. at server shutdown). A throwing sweep is logged and the
 * loop continues — a transient eviction failure must not stop future sweeps.
 */
class EvictionScheduler(
    private val sweep: suspend () -> Int,
    private val interval: Duration,
) {
    private val log = LoggerFactory.getLogger(EvictionScheduler::class.java)

    /** Launches the sweep loop in [scope]. Returns the [Job] for cancellation. */
    fun launchIn(scope: CoroutineScope): Job =
        scope.launch {
            while (isActive) {
                delay(interval.toMillis())
                runCatching { sweep() }
                    .onFailure { cause ->
                        // Never swallow cancellation — let scope shutdown propagate.
                        if (cause is CancellationException) throw cause
                        log.warn("eviction sweep failed", cause)
                    }
            }
        }
}
