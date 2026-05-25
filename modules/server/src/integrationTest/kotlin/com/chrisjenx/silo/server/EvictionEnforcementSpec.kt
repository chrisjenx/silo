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
package com.chrisjenx.silo.server

import com.chrisjenx.silo.metadata.sqlite.SqliteMetadataIndex
import com.chrisjenx.silo.protocol.CacheKey
import com.chrisjenx.silo.storage.EvictionCaps
import com.chrisjenx.silo.storage.EvictionEngine
import com.chrisjenx.silo.storage.EvictionScheduler
import com.chrisjenx.silo.storage.fs.FileSystemCacheStore
import com.chrisjenx.silo.testing.TestKeys
import com.chrisjenx.silo.testing.TmpCacheRoot
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import java.time.Duration

private const val CAP_BYTES = 3L * 1024 * 1024
private const val ENTRY_BODY_BYTES = 1024 * 1024
private const val ENTRY_COUNT = 8
private const val SWEEP_INTERVAL_MS = 50L
private const val MAX_POLL_ATTEMPTS = 60

class EvictionEnforcementSpec : BehaviorSpec({

    Given("a byte-capped store with a fast eviction sweeper") {
        When("more bytes are written than the cap allows") {
            Then("the sweeper drives total stored bytes back under the cap") {
                TmpCacheRoot.create("silo-evict-enforce-").use { root ->
                    runBlocking {
                        val index = SqliteMetadataIndex.open(root.resolve("silo.db"))
                        val store =
                            FileSystemCacheStore(
                                root = root,
                                metadataIndex = index,
                                fsyncDirOnRename = false,
                            )
                        val engine =
                            EvictionEngine(
                                store = store,
                                index = index,
                                caps = EvictionCaps(maxBytes = CAP_BYTES, maxEntries = Long.MAX_VALUE),
                            )
                        val job = EvictionScheduler(engine::sweep, Duration.ofMillis(SWEEP_INTERVAL_MS)).launchIn(this)

                        val nowMs = System.currentTimeMillis()
                        repeat(ENTRY_COUNT) { i ->
                            val key = CacheKey.requireValid(TestKeys.valid(seed = i.toLong()))
                            val body = ByteArray(ENTRY_BODY_BYTES)
                            store.put(key, body.size.toLong(), Buffer().apply { write(body) })
                            // FileSystemCacheStore does not update the metadata index on put;
                            // we mirror the write so EvictionEngine can see the byte totals.
                            index.upsert(
                                key = key,
                                sizeBytes = body.size.toLong(),
                                insertedAtMs = nowMs,
                                lastAccessMs = nowMs + i,
                                contentSha256 = null,
                            )
                        }

                        var bytes = Long.MAX_VALUE
                        for (attempt in 0 until MAX_POLL_ATTEMPTS) {
                            bytes = index.aggregate().bytesStored
                            if (bytes <= CAP_BYTES) break
                            delay(SWEEP_INTERVAL_MS)
                        }
                        job.cancel()
                        index.close()
                        (bytes <= CAP_BYTES) shouldBe true
                    }
                }
            }
        }
    }
})
