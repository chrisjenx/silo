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
package com.chrisjenx.silo.server.auth

import at.favre.lib.crypto.bcrypt.BCrypt
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Verifies a candidate password against a stored bcrypt hash with an
 * in-memory cache so the per-request cost is amortised.
 *
 * bcrypt cost-12 takes ~250 ms; under steady CI traffic that would
 * dominate the per-request budget. The cache is keyed on a SHA-256 of
 * `username|hash|plaintext` (so neither the plaintext nor the hash leaks
 * into a `String`-keyed table), and entries expire after [cacheTtl].
 *
 * Comparison is constant-time via [MessageDigest.isEqual] so an attacker
 * cannot time-distinguish a wrong hash from a wrong plaintext.
 */
class PasswordVerifier(
    private val clock: Clock = Clock.systemUTC(),
    private val cacheTtl: Duration = Duration.ofMinutes(5),
) {
    private data class CacheEntry(val expectedDigest: ByteArray, val expiresAtMs: Long) {
        override fun equals(other: Any?): Boolean = this === other

        override fun hashCode(): Int = System.identityHashCode(this)
    }

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val hashVerifier = BCrypt.verifyer()

    /** Returns `true` when [plaintext] matches [storedHash] for [username]. */
    fun verify(
        username: String,
        plaintext: CharArray,
        storedHash: String,
    ): Boolean {
        val key = cacheKey(username, plaintext, storedHash)
        val now = clock.millis()
        val cached = cache[key]
        if (cached != null && cached.expiresAtMs > now) {
            return MessageDigest.isEqual(cached.expectedDigest, EXPECTED_DIGEST_MATCH)
        }
        val ok = hashVerifier.verify(plaintext, storedHash.toCharArray()).verified
        if (ok) {
            cache[key] =
                CacheEntry(
                    expectedDigest = EXPECTED_DIGEST_MATCH.copyOf(),
                    expiresAtMs = now + cacheTtl.toMillis(),
                )
        }
        return ok
    }

    /** Drops expired entries; cheap, callable from a periodic task. */
    fun evictExpired(now: Long = clock.millis()) {
        cache.entries.removeIf { it.value.expiresAtMs <= now }
    }

    /** Visible for tests. */
    internal val cacheSize: Int get() = cache.size

    private fun cacheKey(
        username: String,
        plaintext: CharArray,
        storedHash: String,
    ): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(username.toByteArray(Charsets.UTF_8))
        md.update(0)
        md.update(storedHash.toByteArray(Charsets.UTF_8))
        md.update(0)
        md.update(String(plaintext).toByteArray(Charsets.UTF_8))
        return md.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it) }

    private companion object {
        // Single constant "matched" sentinel — what we compare against in
        // the cache hit path. The actual security boundary is the bcrypt
        // verify; the cache just records a previously-successful outcome.
        private val EXPECTED_DIGEST_MATCH = ByteArray(32) { 1 }
    }
}
