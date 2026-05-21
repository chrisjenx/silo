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
import com.chrisjenx.silo.storage.EntryStatus
import com.chrisjenx.silo.testing.TestKeys
import com.chrisjenx.silo.testing.TmpCacheRoot
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.sql.DriverManager

class SqliteMetadataIndexSpec : BehaviorSpec({

    given("a fresh on-disk SqliteMetadataIndex") {
        `when`("an entry is upserted and read back") {
            then("the round-trip preserves all stored fields") {
                TmpCacheRoot.create("silo-meta-rt-").use { root ->
                    val index = SqliteMetadataIndex.open(root.resolve("silo.db"))
                    val key = CacheKey.requireValid(TestKeys.valid(seed = 1))
                    val sha = ByteArray(32) { it.toByte() }
                    index.upsert(key, sizeBytes = 1024, insertedAtMs = 100, contentSha256 = sha)

                    val record = index.get(key).shouldNotBeNull()
                    record.key shouldBe key
                    record.sizeBytes shouldBe 1024L
                    record.insertedAtMs shouldBe 100L
                    record.lastAccessMs shouldBe 100L
                    record.contentSha256!!.toList() shouldBe sha.toList()
                    record.status shouldBe EntryStatus.COMMITTED
                    index.close()
                }
            }
        }

        `when`("touch() is called before flush()") {
            then("the value is durable only after flush()") {
                TmpCacheRoot.create("silo-meta-touch-").use { root ->
                    val dbPath = root.resolve("silo.db")
                    val index = SqliteMetadataIndex.open(dbPath)
                    val key = CacheKey.requireValid(TestKeys.valid(seed = 2))
                    index.upsert(key, 16, 1000)

                    index.touch(key, 5000)
                    // Touch is buffered: a parallel reader sees the old value.
                    index.get(key)?.lastAccessMs shouldBe 1000L

                    index.flush()
                    index.get(key)?.lastAccessMs shouldBe 5000L
                    index.close()
                }
            }
        }

        `when`("aggregate() is called over multiple rows") {
            then("it sums committed entries only") {
                TmpCacheRoot.create("silo-meta-agg-").use { root ->
                    val index = SqliteMetadataIndex.open(root.resolve("silo.db"))
                    val k1 = CacheKey.requireValid(TestKeys.valid(seed = 3))
                    val k2 = CacheKey.requireValid(TestKeys.valid(seed = 4))
                    index.upsert(k1, 100, 1)
                    index.upsert(k2, 200, 2)

                    val agg = index.aggregate()
                    agg.entryCount shouldBe 2L
                    agg.bytesStored shouldBe 300L
                    index.close()
                }
            }
        }

        `when`("delete() is called for an existing row") {
            then("the row vanishes and pending touches for that key are dropped") {
                TmpCacheRoot.create("silo-meta-del-").use { root ->
                    val index = SqliteMetadataIndex.open(root.resolve("silo.db"))
                    val key = CacheKey.requireValid(TestKeys.valid(seed = 5))
                    index.upsert(key, 16, 1)
                    index.touch(key, 99)

                    index.delete(key) shouldBe true
                    index.get(key) shouldBe null
                    index.delete(key) shouldBe false
                    index.close()
                }
            }
        }

        `when`("the index is reopened against the same file") {
            then("schema migrations are idempotent and prior rows persist") {
                TmpCacheRoot.create("silo-meta-reopen-").use { root ->
                    val dbPath = root.resolve("silo.db")
                    val first = SqliteMetadataIndex.open(dbPath)
                    val key = CacheKey.requireValid(TestKeys.valid(seed = 6))
                    first.upsert(key, 32, 7)
                    first.close()

                    val second = SqliteMetadataIndex.open(dbPath)
                    second.get(key).shouldNotBeNull().sizeBytes shouldBe 32L

                    DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { conn ->
                        conn.createStatement().executeQuery("SELECT MAX(version) FROM schema_version").use { rs ->
                            rs.next()
                            rs.getInt(1) shouldBe 1
                        }
                    }
                    second.close()
                }
            }
        }
    }
})

private fun TmpCacheRoot.resolve(name: String) = path.resolve(name)
