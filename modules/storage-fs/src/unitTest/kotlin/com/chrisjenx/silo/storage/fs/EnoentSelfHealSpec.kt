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

class EnoentSelfHealSpec : StringSpec({

    "out-of-band rm of a blob purges the SQLite row on next GET" {
        TmpCacheRoot.create("silo-enoent-").use { root ->
            val index = SqliteMetadataIndex.open(root.resolve("silo.db"))
            val store =
                FileSystemCacheStore(
                    root = root,
                    fsyncDirOnRename = false,
                    metadataIndex = index,
                )
            val key = CacheKey.requireValid(TestKeys.valid(seed = 1))

            runBlocking {
                store.put(key, 4, Buffer().apply { write(ByteArray(4)) })
                index.upsert(key, 4, insertedAtMs = 1)
                // Simulate an out-of-band delete (e.g. someone ran `rm`).
                Files.deleteIfExists(ShardLayout.finalPath(root, key))

                store.get(key) shouldBe null
                index.get(key) shouldBe null
                store.enoentDriftCount shouldBe 1L
            }
            index.close()
        }
    }

    "missing key with no SQLite row is a plain cache miss (no drift counter bump)" {
        TmpCacheRoot.create("silo-enoent-plain-").use { root ->
            val index = SqliteMetadataIndex.open(root.resolve("silo.db"))
            val store =
                FileSystemCacheStore(
                    root = root,
                    fsyncDirOnRename = false,
                    metadataIndex = index,
                )
            val key = CacheKey.requireValid(TestKeys.valid(seed = 2))
            runBlocking {
                store.get(key) shouldBe null
                store.enoentDriftCount shouldBe 0L
            }
            index.close()
        }
    }
})
