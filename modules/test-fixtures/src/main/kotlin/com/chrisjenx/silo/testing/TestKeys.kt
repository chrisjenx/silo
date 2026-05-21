/*
 * Copyright (c) 2026 Chris Jenkins
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
package com.chrisjenx.silo.testing

import kotlin.random.Random

/**
 * Deterministic and random hex-key generators for cache-store tests.
 *
 * Gradle keys are hex strings; CLAUDE.md specifies the regex `^[a-f0-9]{8,128}$`.
 * These helpers stay inside that envelope.
 */
object TestKeys {
    private const val HEX = "0123456789abcdef"

    fun valid(
        length: Int = 64,
        seed: Long? = null,
    ): String {
        require(length in 8..128) { "key length must be in [8, 128], was $length" }
        val rng = if (seed != null) Random(seed) else Random.Default
        return buildString(length) { repeat(length) { append(HEX[rng.nextInt(16)]) } }
    }

    fun sequence(
        count: Int,
        length: Int = 64,
    ): List<String> = (0 until count).map { valid(length, seed = it.toLong()) }

    val tooShort: String = "abc"
    val tooLong: String = HEX.repeat(20).take(129)
    val pathTraversal: String = "../etc/passwd"
    val mixedCase: String = "ABCDEF0123456789".repeat(4)
    val nonHex: String = "g".repeat(64)
}
