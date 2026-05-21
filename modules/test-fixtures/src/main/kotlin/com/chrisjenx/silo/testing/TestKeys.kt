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
