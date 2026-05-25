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

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

class EvictionSchedulerSpec : StringSpec({

    "launchIn runs the sweep repeatedly until the scope is cancelled" {
        runBlocking {
            val count = AtomicInteger(0)
            val job =
                EvictionScheduler(
                    sweep = {
                        count.incrementAndGet()
                        0
                    },
                    interval = Duration.ofMillis(20),
                ).launchIn(this)
            delay(120)
            job.cancel()
            count.get() shouldBeGreaterThanOrEqual 2
        }
    }

    "a failing sweep is logged but does not kill the loop" {
        runBlocking {
            val count = AtomicInteger(0)
            val job =
                EvictionScheduler(
                    sweep = {
                        val n = count.incrementAndGet()
                        if (n == 1) error("boom")
                        0
                    },
                    interval = Duration.ofMillis(20),
                ).launchIn(this)
            delay(120)
            job.cancel()
            count.get() shouldBeGreaterThanOrEqual 2
        }
    }
})
