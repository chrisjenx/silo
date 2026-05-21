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
package com.chrisjenx.silo.metrics

import com.chrisjenx.silo.storage.fs.FileSystemCacheStore
import com.chrisjenx.silo.storage.fs.ReconciliationEngine
import com.chrisjenx.silo.testing.TmpCacheRoot
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class PrometheusFactorySpec : StringSpec({

    "create attaches env + instance tags as common tags" {
        val registry = PrometheusFactory.create(env = "ci", instance = "silo-test-1")
        val sample = registry.counter("silo_test_sample").also { it.increment() }
        sample.count() shouldBe 1.0
        val scrape = registry.scrape()
        scrape shouldContain "env=\"ci\""
        scrape shouldContain "instance=\"silo-test-1\""
    }

    "bindSilo registers store + reconcile gauges that read live counters" {
        TmpCacheRoot.create("silo-metrics-").use { root ->
            val store = FileSystemCacheStore(root = root, fsyncDirOnRename = false)
            val reconcile = ReconciliationEngine(root = root, index = inMemoryIndex())
            val registry = PrometheusFactory.create("test", "test")
            registry.bindSilo(cacheStore = store, reconciliationEngine = reconcile)
            val scrape = registry.scrape()
            scrape shouldContain "silo_storage_cross_fs_rename"
            scrape shouldContain "silo_drift_detected"
        }
    }

    "bindSilo also registers eviction gauges when an engine is supplied" {
        TmpCacheRoot.create("silo-metrics-evict-").use { root ->
            val store = FileSystemCacheStore(root = root, fsyncDirOnRename = false)
            val eviction =
                com.chrisjenx.silo.storage.EvictionEngine(
                    store = store,
                    index = inMemoryIndex(),
                    caps =
                        com.chrisjenx.silo.storage.EvictionCaps(
                            maxBytes = Long.MAX_VALUE,
                            maxEntries = Long.MAX_VALUE,
                        ),
                )
            val registry = PrometheusFactory.create("test", "test")
            registry.bindSilo(cacheStore = store, evictionEngine = eviction)
            registry.scrape() shouldContain "silo_store_evictions"
        }
    }
})

private fun inMemoryIndex(): com.chrisjenx.silo.storage.MetadataIndex =
    object : com.chrisjenx.silo.storage.MetadataIndex {
        override suspend fun upsert(
            key: com.chrisjenx.silo.protocol.CacheKey,
            sizeBytes: Long,
            insertedAtMs: Long,
            lastAccessMs: Long,
            contentSha256: ByteArray?,
        ) = Unit

        override suspend fun touch(
            key: com.chrisjenx.silo.protocol.CacheKey,
            accessedAtMs: Long,
        ) = Unit

        override suspend fun get(key: com.chrisjenx.silo.protocol.CacheKey) = null

        override suspend fun delete(key: com.chrisjenx.silo.protocol.CacheKey) = false

        override suspend fun aggregate() = com.chrisjenx.silo.storage.MetadataAggregate(0, 0)

        override suspend fun lruVictims(limit: Int) = emptyList<com.chrisjenx.silo.storage.EntryRecord>()

        override suspend fun expiredVictims(
            olderThanMs: Long,
            limit: Int,
        ) = emptyList<com.chrisjenx.silo.storage.EntryRecord>()

        override suspend fun pageKeysAfter(
            after: String?,
            limit: Int,
        ) = emptyList<com.chrisjenx.silo.protocol.CacheKey>()

        override suspend fun flush() = Unit

        override fun close() = Unit
    }
