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

import com.chrisjenx.silo.metadata.sqlite.SqliteMetadataIndex
import com.chrisjenx.silo.protocol.CacheKey
import com.chrisjenx.silo.testing.TestKeys
import com.chrisjenx.silo.testing.TmpCacheRoot
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.write
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ReconciliationEngineSpec : StringSpec({

    "orphan blob on disk is re-indexed with its mtime" {
        TmpCacheRoot.create("silo-reconcile-blob-").use { root ->
            val index = SqliteMetadataIndex.open(root.resolve("silo.db"))
            val store = FileSystemCacheStore(root = root, fsyncDirOnRename = false)
            val key = CacheKey.requireValid(TestKeys.valid(seed = 1))
            // Bypass the store API to simulate an out-of-band copy: write the blob
            // through the store, then delete the SQLite row so only the disk side
            // remains.
            runBlocking {
                store.put(key, 4, Buffer().apply { write(ByteArray(4)) })
                index.delete(key)
                ReconciliationEngine(root = root, index = index).reconcile().also {
                    it.orphanBlobsReindexed shouldBe 1
                    it.orphanRowsDeleted shouldBe 0
                    it.staleTmpDeleted shouldBe 0
                }
                (index.get(key) != null) shouldBe true
            }
            index.close()
        }
    }

    "orphan row with no blob on disk is purged" {
        TmpCacheRoot.create("silo-reconcile-row-").use { root ->
            val index = SqliteMetadataIndex.open(root.resolve("silo.db"))
            val key = CacheKey.requireValid(TestKeys.valid(seed = 2))
            runBlocking {
                index.upsert(key, sizeBytes = 100, insertedAtMs = 1)
                ReconciliationEngine(root = root, index = index).reconcile().also {
                    it.orphanRowsDeleted shouldBe 1
                }
                index.get(key) shouldBe null
            }
            index.close()
        }
    }

    "stale tmp file older than the cutoff is deleted" {
        TmpCacheRoot.create("silo-reconcile-tmp-").use { root ->
            val index = SqliteMetadataIndex.open(root.resolve("silo.db"))
            val key = CacheKey.requireValid(TestKeys.valid(seed = 3))
            val store = FileSystemCacheStore(root = root, fsyncDirOnRename = false)
            // Force the shard dir into existence by writing a real blob.
            runBlocking { store.put(key, 4, Buffer().apply { write(ByteArray(4)) }) }
            val shard = ShardLayout.shardDir(root, key)
            val staleTmp = shard.resolve("tmp.${key.value}.zombie")
            Files.write(staleTmp, ByteArray(8))
            // Pretend it was written 15 minutes ago.
            Files.setLastModifiedTime(staleTmp, FileTime.fromMillis(System.currentTimeMillis() - 15L * 60_000))

            val clock = Clock.fixed(Instant.ofEpochMilli(System.currentTimeMillis()), ZoneOffset.UTC)
            runBlocking {
                ReconciliationEngine(root = root, index = index, clock = clock).reconcile().also {
                    it.staleTmpDeleted shouldBe 1
                }
            }
            Files.exists(staleTmp) shouldBe false
            index.close()
        }
    }
})
