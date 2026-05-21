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

import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

/**
 * Caps the [EvictionEngine] enforces. Reserved-free-bytes and inode
 * thresholds are tracked in separate issues; this v1 covers the LRU-byte
 * and LRU-entry-count caps that the bootstrap plan needs first.
 */
data class EvictionCaps(
    /** Maximum total bytes the store may hold. */
    val maxBytes: Long,
    /** Maximum total committed entries the store may hold. */
    val maxEntries: Long,
    /** Per-cycle delete budget so a tight overshoot doesn't I/O-storm. */
    val maxDeletesPerCycle: Int = DEFAULT_MAX_DELETES_PER_CYCLE,
) {
    companion object {
        const val DEFAULT_MAX_DELETES_PER_CYCLE: Int = 1_000
    }
}

/** Reasons surfaced to metrics so operators can see why a victim went. */
enum class EvictionReason { BYTE_CAP, ENTRY_CAP }

/**
 * Picks the least-recently-accessed entries from the [MetadataIndex] and
 * deletes them from both the index and the [CacheStore] until the caps
 * are satisfied. Each sweep is bounded by [EvictionCaps.maxDeletesPerCycle]
 * so the engine cannot dominate disk IOPS even when way over the cap.
 */
class EvictionEngine(
    private val store: CacheStore,
    private val index: MetadataIndex,
    private val caps: EvictionCaps,
) {
    private val log = LoggerFactory.getLogger(EvictionEngine::class.java)
    private val byteCapEvictions = AtomicLong(0)
    private val entryCapEvictions = AtomicLong(0)

    /** Evictions attributable to [reason] over the lifetime of this engine. */
    fun evictionsFor(reason: EvictionReason): Long =
        when (reason) {
            EvictionReason.BYTE_CAP -> byteCapEvictions.get()
            EvictionReason.ENTRY_CAP -> entryCapEvictions.get()
        }

    /**
     * Runs one budgeted sweep. Returns the total number of entries
     * evicted (across both reasons).
     */
    suspend fun sweep(): Int {
        var deleted = 0
        var budget = caps.maxDeletesPerCycle
        var keepGoing = budget > 0
        while (keepGoing) {
            keepGoing = runOneSweepPass(budget)?.also { (n, reason) ->
                deleted += n
                budget -= n
                repeat(n) { bumpCounter(reason) }
            } != null && budget > 0
        }
        if (deleted > 0) {
            log.info(
                "eviction sweep deleted {} entries (byteCap={} entryCap={})",
                deleted,
                byteCapEvictions.get(),
                entryCapEvictions.get(),
            )
        }
        return deleted
    }

    /**
     * Returns `(victimsDeleted, reason)` for one pass, or `null` when the
     * caps are satisfied and the sweep is done.
     */
    private suspend fun runOneSweepPass(budget: Int): Pair<Int, EvictionReason>? {
        val agg = index.aggregate()
        val overBytes = agg.bytesStored > caps.maxBytes
        val overEntries = agg.entryCount > caps.maxEntries
        if (!overBytes && !overEntries) return null
        val reason = if (overBytes) EvictionReason.BYTE_CAP else EvictionReason.ENTRY_CAP
        val batchTarget = minOf(EVICTION_BATCH_SIZE, budget)
        val victims = index.lruVictims(batchTarget).take(budget)
        if (victims.isEmpty()) return null
        victims.forEach { v ->
            store.delete(v.key)
            index.delete(v.key)
        }
        return victims.size to reason
    }

    private fun bumpCounter(reason: EvictionReason) {
        when (reason) {
            EvictionReason.BYTE_CAP -> byteCapEvictions.incrementAndGet()
            EvictionReason.ENTRY_CAP -> entryCapEvictions.incrementAndGet()
        }
    }

    private companion object {
        // One-at-a-time deletes so the cap check re-runs after every
        // victim — over-aggressive batches would push the cache below the
        // cap rather than barely under it.
        private const val EVICTION_BATCH_SIZE: Int = 1
    }
}
