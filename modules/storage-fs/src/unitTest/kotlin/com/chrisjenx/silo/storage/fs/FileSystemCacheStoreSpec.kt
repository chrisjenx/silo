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
import com.chrisjenx.silo.testing.TestKeys
import com.chrisjenx.silo.testing.TmpCacheRoot
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.io.Buffer
import kotlinx.io.write
import java.nio.file.Files

class FileSystemCacheStoreSpec : StringSpec({

    "delete returns true when the key was present and false otherwise" {
        TmpCacheRoot.create(prefix = "silo-fs-delete-").use { root ->
            val store = FileSystemCacheStore(root = root, fsyncDirOnRename = false)
            val key = CacheKey.requireValid(TestKeys.valid(seed = 100))
            store.delete(key) shouldBe false
            store.put(key, 16, Buffer().apply { write(ByteArray(16) { it.toByte() }) })
            store.delete(key) shouldBe true
            store.has(key) shouldBe false
        }
    }

    "stats reports entry count and total bytes, plus live counters" {
        TmpCacheRoot.create(prefix = "silo-fs-stats-").use { root ->
            val store = FileSystemCacheStore(root = root, fsyncDirOnRename = false)
            val key1 = CacheKey.requireValid(TestKeys.valid(seed = 200))
            val key2 = CacheKey.requireValid(TestKeys.valid(seed = 201))

            store.put(key1, 16, Buffer().apply { write(ByteArray(16) { 1.toByte() }) })
            store.put(key2, 32, Buffer().apply { write(ByteArray(32) { 2.toByte() }) })

            store.get(key1)?.close()
            store.get(CacheKey.requireValid(TestKeys.valid(seed = 999)))?.close()

            val stats = store.stats()
            stats.entryCount shouldBe 2L
            stats.bytesStored shouldBe 48L
            stats.hits shouldBe 1L
            stats.misses shouldBe 1L
            stats.puts shouldBe 2L
            stats.evictions shouldBe 0L
        }
    }

    "stats ignores stale tmp.* files left in the shard dirs" {
        TmpCacheRoot.create(prefix = "silo-fs-tmp-").use { root ->
            val store = FileSystemCacheStore(root = root, fsyncDirOnRename = false)
            val key = CacheKey.requireValid(TestKeys.valid(seed = 300))
            store.put(key, 8, Buffer().apply { write(ByteArray(8) { 7.toByte() }) })

            // Simulate a crashed PUT leaving a tmp file behind in the shard.
            val shard = ShardLayout.shardDir(root, key)
            Files.write(shard.resolve("tmp.${key.value}.zombie"), ByteArray(99))

            val stats = store.stats()
            stats.entryCount shouldBe 1L
            stats.bytesStored shouldBe 8L
        }
    }
})

/**
 * Convenience overloads so the specs above can write `store.put(key, n, buf)`
 * without converting [Buffer] to [kotlinx.io.RawSource] each time, and pass a
 * [TmpCacheRoot] anywhere a `java.nio.file.Path` is wanted.
 */
private suspend fun FileSystemCacheStore.put(
    key: CacheKey,
    size: Long,
    body: Buffer,
) = put(key, size, body as kotlinx.io.RawSource)

private fun TmpCacheRoot.toPath(): java.nio.file.Path = path

private fun FileSystemCacheStore(
    root: TmpCacheRoot,
    fsyncDirOnRename: Boolean,
): FileSystemCacheStore = FileSystemCacheStore(root = root.path, fsyncDirOnRename = fsyncDirOnRename)

private fun com.chrisjenx.silo.storage.fs.ShardLayout.shardDir(
    root: TmpCacheRoot,
    key: CacheKey,
): java.nio.file.Path = shardDir(root.path, key)
