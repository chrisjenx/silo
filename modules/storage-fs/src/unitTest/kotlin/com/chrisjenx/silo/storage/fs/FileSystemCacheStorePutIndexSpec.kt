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
package com.chrisjenx.silo.storage.fs

import com.chrisjenx.silo.protocol.CacheKey
import com.chrisjenx.silo.storage.EntryRecord
import com.chrisjenx.silo.storage.EntryStatus
import com.chrisjenx.silo.storage.MetadataAggregate
import com.chrisjenx.silo.storage.MetadataIndex
import com.chrisjenx.silo.testing.TmpCacheRoot
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer

class FileSystemCacheStorePutIndexSpec : StringSpec({

    "put writes a committed metadata row sized to the stored bytes" {
        runBlocking {
            TmpCacheRoot.create("silo-put-index-").use { root ->
                val index = RecordingIndex()
                val store =
                    FileSystemCacheStore(
                        root = root,
                        metadataIndex = index,
                        fsyncDirOnRename = false,
                    )
                val key = CacheKey.requireValid("a".repeat(64))
                store.put(key, 2_048L, Buffer().apply { write(ByteArray(2_048)) })

                index.get(key) shouldNotBe null
                index.get(key)!!.sizeBytes shouldBe 2_048L
                index.aggregate() shouldBe MetadataAggregate(entryCount = 1, bytesStored = 2_048L)
            }
        }
    }
})

private class RecordingIndex : MetadataIndex {
    private val rows = linkedMapOf<String, EntryRecord>()

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
        rows[key.value]?.let { rows[key.value] = it.copy(lastAccessMs = accessedAtMs) }
    }

    override suspend fun get(key: CacheKey): EntryRecord? = rows[key.value]

    override suspend fun delete(key: CacheKey): Boolean = rows.remove(key.value) != null

    override suspend fun aggregate(): MetadataAggregate {
        val committed = rows.values.filter { it.status == EntryStatus.COMMITTED }
        return MetadataAggregate(committed.size.toLong(), committed.sumOf { it.sizeBytes })
    }

    override suspend fun lruVictims(limit: Int): List<EntryRecord> =
        rows.values
            .sortedBy { it.lastAccessMs }
            .take(limit)

    override suspend fun expiredVictims(
        olderThanMs: Long,
        limit: Int,
    ): List<EntryRecord> =
        rows.values
            .filter { it.lastAccessMs < olderThanMs }
            .sortedBy { it.lastAccessMs }
            .take(limit)

    override suspend fun pageKeysAfter(
        after: String?,
        limit: Int,
    ): List<CacheKey> =
        rows.values
            .map { it.key }
            .filter { it.value > (after ?: "") }
            .sortedBy { it.value }
            .take(limit)

    override suspend fun flush() {}

    override fun close() {}
}
