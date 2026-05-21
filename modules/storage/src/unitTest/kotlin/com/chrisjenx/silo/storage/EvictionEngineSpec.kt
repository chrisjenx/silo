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

import com.chrisjenx.silo.protocol.CacheKey
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.io.RawSource

class EvictionEngineSpec : StringSpec({

    "sweep deletes oldest first until under byte cap, respecting the budget" {
        runBlocking {
            val store = RecordingStore()
            val index =
                InMemoryMetadataIndex().apply {
                    seed("0".repeat(64), size = 30, ts = 1)
                    seed("1".repeat(64), size = 30, ts = 2)
                    seed("2".repeat(64), size = 30, ts = 3)
                    seed("3".repeat(64), size = 30, ts = 4)
                }
            val engine =
                EvictionEngine(
                    store = store,
                    index = index,
                    caps = EvictionCaps(maxBytes = 60, maxEntries = Long.MAX_VALUE),
                )

            val n = engine.sweep()
            n shouldBe 2
            store.deleted.first().value shouldBe "0".repeat(64)
            store.deleted[1].value shouldBe "1".repeat(64)
            engine.evictionsFor(EvictionReason.BYTE_CAP) shouldBe 2L
            engine.evictionsFor(EvictionReason.ENTRY_CAP) shouldBe 0L
        }
    }

    "sweep evicts under the entry cap when byte cap is not the bottleneck" {
        runBlocking {
            val store = RecordingStore()
            val index =
                InMemoryMetadataIndex().apply {
                    seed("a".repeat(16), size = 1, ts = 1)
                    seed("b".repeat(16), size = 1, ts = 2)
                    seed("c".repeat(16), size = 1, ts = 3)
                }
            val engine =
                EvictionEngine(
                    store = store,
                    index = index,
                    caps = EvictionCaps(maxBytes = Long.MAX_VALUE, maxEntries = 1),
                )
            engine.sweep() shouldBe 2
            engine.evictionsFor(EvictionReason.ENTRY_CAP) shouldBe 2L
        }
    }

    "sweep stops at the maxDeletesPerCycle budget even if still over cap" {
        runBlocking {
            val store = RecordingStore()
            val index =
                InMemoryMetadataIndex().apply {
                    for (i in 0 until 10) seed("%064x".format(i), size = 10, ts = i.toLong())
                }
            val engine =
                EvictionEngine(
                    store = store,
                    index = index,
                    caps = EvictionCaps(maxBytes = 0, maxEntries = 0, maxDeletesPerCycle = 3),
                )
            engine.sweep() shouldBe 3
            store.deleted.size shouldBe 3
        }
    }
})

private class RecordingStore : CacheStore {
    val deleted = mutableListOf<CacheKey>()

    override suspend fun get(key: CacheKey) = null

    override suspend fun put(
        key: CacheKey,
        size: Long,
        body: RawSource,
    ): PutOutcome = PutOutcome.Stored(size)

    override suspend fun has(key: CacheKey) = false

    override suspend fun delete(key: CacheKey): Boolean {
        deleted += key
        return true
    }

    override suspend fun stats(): CacheStats = CacheStats.EMPTY
}

private class InMemoryMetadataIndex : MetadataIndex {
    private val rows = linkedMapOf<String, EntryRecord>()

    fun seed(
        key: String,
        size: Long,
        ts: Long,
    ) {
        val k = CacheKey.requireValid(key)
        rows[key] = EntryRecord(k, size, ts, ts, null, EntryStatus.COMMITTED)
    }

    override suspend fun upsert(
        key: CacheKey,
        sizeBytes: Long,
        insertedAtMs: Long,
        lastAccessMs: Long,
        contentSha256: ByteArray?,
    ) {
        rows[key.value] = EntryRecord(key, sizeBytes, insertedAtMs, lastAccessMs, contentSha256, EntryStatus.COMMITTED)
    }

    override suspend fun touch(
        key: CacheKey,
        accessedAtMs: Long,
    ) {
        val existing = rows[key.value] ?: return
        rows[key.value] = existing.copy(lastAccessMs = accessedAtMs)
    }

    override suspend fun get(key: CacheKey): EntryRecord? = rows[key.value]

    override suspend fun delete(key: CacheKey): Boolean = rows.remove(key.value) != null

    override suspend fun aggregate(): MetadataAggregate {
        val committed = rows.values.filter { it.status == EntryStatus.COMMITTED }
        return MetadataAggregate(committed.size.toLong(), committed.sumOf { it.sizeBytes })
    }

    override suspend fun lruVictims(limit: Int): List<EntryRecord> =
        rows.values
            .filter { it.status == EntryStatus.COMMITTED }
            .sortedBy { it.lastAccessMs }
            .take(limit)

    override suspend fun flush() {}

    override fun close() {}
}
