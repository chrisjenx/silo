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
package com.chrisjenx.silo.metadata.sqlite

import com.chrisjenx.silo.protocol.CacheKey
import com.chrisjenx.silo.testing.TestKeys
import com.chrisjenx.silo.testing.TmpCacheRoot
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking

class SqliteUpsertBatchingSpec : StringSpec({

    // 1. Read-after-write: single upsert (below threshold) is immediately
    //    visible via get() (buffer merge) and aggregate() (flush-first).
    "upsert is visible via get() before flush and reflected in aggregate()" {
        TmpCacheRoot.create("silo-upsert-raw-").use { dir ->
            val index = SqliteMetadataIndex.open(dir.resolve("silo.db"))
            val key = CacheKey.requireValid("a".repeat(64))
            runBlocking {
                index.upsert(key, sizeBytes = 512, insertedAtMs = 1000)

                // Buffer merge: no DB round-trip needed.
                val record = index.get(key).shouldNotBeNull()
                record.key shouldBe key
                record.sizeBytes shouldBe 512L
                record.insertedAtMs shouldBe 1000L
                record.lastAccessMs shouldBe 1000L

                // aggregate() flushes first → sees the buffered upsert.
                val agg = index.aggregate()
                agg.entryCount shouldBe 1L
                agg.bytesStored shouldBe 512L
            }
            index.close()
        }
    }

    // 2. Buffered entry is deletable before it reaches the DB.
    "upsert then delete: get() returns null and aggregate reflects zero" {
        TmpCacheRoot.create("silo-upsert-del-").use { dir ->
            val index = SqliteMetadataIndex.open(dir.resolve("silo.db"))
            val key = CacheKey.requireValid("b".repeat(64))
            runBlocking {
                index.upsert(key, sizeBytes = 256, insertedAtMs = 2000)
                // Confirm the entry is in the buffer.
                index.get(key).shouldNotBeNull()

                // delete() must drain the buffer AND return true even though
                // nothing has been written to SQLite yet.
                index.delete(key) shouldBe true
                index.get(key).shouldBeNull()

                val agg = index.aggregate()
                agg.entryCount shouldBe 0L
            }
            index.close()
        }
    }

    // 3. Durability: close() flushes the buffer so entries survive a reopen.
    "close() flushes buffered upserts; reopened index sees all entries" {
        TmpCacheRoot.create("silo-upsert-dur-").use { dir ->
            val dbPath = dir.resolve("silo.db")
            val keys =
                (0 until 5).map { i ->
                    CacheKey.requireValid(TestKeys.valid(seed = (100 + i).toLong()))
                }

            // Write several entries and close without explicit flush.
            val first = SqliteMetadataIndex.open(dbPath)
            runBlocking {
                keys.forEach { k ->
                    first.upsert(k, sizeBytes = 128, insertedAtMs = 3000)
                }
            }
            first.close() // must flush buffered upserts

            // Reopen and verify all entries are present.
            val second = SqliteMetadataIndex.open(dbPath)
            runBlocking {
                keys.forEach { k ->
                    second.get(k).shouldNotBeNull().sizeBytes shouldBe 128L
                }
                val agg = second.aggregate()
                agg.entryCount shouldBe keys.size.toLong()
            }
            second.close()
        }
    }
})

private fun TmpCacheRoot.resolve(name: String) = path.resolve(name)
