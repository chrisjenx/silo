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
package com.chrisjenx.silo.bench

import com.chrisjenx.silo.protocol.CacheKey
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State

/**
 * Throughput of [CacheKey.parse] on the HTTP hot path. Every GET/PUT/HEAD
 * validates its key through this regex before touching storage, so the
 * cost lives on the critical path for the whole server.
 *
 * Run with `./gradlew :bench:benchmark`. Target: > 50M ops/s/core for the
 * valid path on a commodity laptop CPU.
 */
@State(Scope.Benchmark)
open class CacheKeyValidationBench {
    private lateinit var validKeys: List<String>
    private lateinit var invalidKeys: List<String>

    @Setup
    fun setup() {
        validKeys = (1..KEY_COUNT).map { hexKey(it) }
        invalidKeys = (1..KEY_COUNT).map { "Z-not-a-valid-key/../$it" }
    }

    /** Happy path: keys that pass the pattern and allocate a [CacheKey]. */
    @Benchmark
    fun parseValid(bh: Blackhole) {
        for (k in validKeys) bh.consume(CacheKey.parse(k))
    }

    /** Rejection path: keys that fail the pattern (returns null, no alloc). */
    @Benchmark
    fun parseInvalid(bh: Blackhole) {
        for (k in invalidKeys) bh.consume(CacheKey.parse(k))
    }

    private companion object {
        const val KEY_COUNT = 1024L
        const val HEX = "0123456789abcdef"

        /** Deterministic 64-char lowercase-hex key from a numeric seed. */
        fun hexKey(seed: Long): String {
            val sb = StringBuilder(64)
            var n = seed
            repeat(64) {
                n = n * 6364136223846793005L + 1442695040888963407L
                sb.append(HEX[((n ushr 60) and 0xF).toInt()])
            }
            return sb.toString()
        }
    }
}
