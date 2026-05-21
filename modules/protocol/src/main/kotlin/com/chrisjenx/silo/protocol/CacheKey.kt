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
package com.chrisjenx.silo.protocol

/**
 * A validated Gradle remote build-cache key.
 *
 * The Gradle build-cache protocol defines a key as an opaque hex hash. Silo
 * accepts any lowercase hex string between 8 and 128 characters (inclusive)
 * to tolerate future Gradle hash-algorithm changes without code edits.
 *
 * Construction is restricted to [CacheKey.parse] (or [CacheKey.requireValid]),
 * which is the single boundary where untrusted input is normalised. Once you
 * hold a `CacheKey`, downstream code can assume the string is safe to use as
 * a filesystem path component — no traversal, no separators, no whitespace.
 */
@JvmInline
value class CacheKey private constructor(val value: String) {
    /** The first 12 chars of the key, intended for log lines. */
    val short: String get() = value.substring(0, 12.coerceAtMost(value.length))

    override fun toString(): String = value

    companion object {
        /** Lowercase hex, 8 to 128 chars inclusive. */
        val PATTERN: Regex = Regex("^[a-f0-9]{8,128}$")

        /** Inclusive lower bound on the key length. */
        const val MIN_LENGTH: Int = 8

        /** Inclusive upper bound on the key length. */
        const val MAX_LENGTH: Int = 128

        /**
         * Parses [raw] into a [CacheKey], returning `null` if the input fails
         * the [PATTERN] check. Callers at the HTTP boundary should map a
         * `null` result to a `400 Bad Request` before touching storage.
         */
        fun parse(raw: String): CacheKey? = if (PATTERN.matches(raw)) CacheKey(raw) else null

        /**
         * Throws [CacheKeyParseException] if [raw] is not a valid key. Use
         * this in tests and code paths where an invalid key is a bug rather
         * than untrusted input.
         */
        fun requireValid(raw: String): CacheKey = parse(raw) ?: throw CacheKeyParseException(raw)
    }
}

/** Thrown by [CacheKey.requireValid] when the input fails the pattern check. */
class CacheKeyParseException(invalid: String) : IllegalArgumentException(
    "Invalid cache key (must match ${CacheKey.PATTERN.pattern}): '$invalid'",
)
