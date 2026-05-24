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
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import java.nio.file.Files
import java.time.Duration

class SqliteMaintenanceSpec : StringSpec({

    "walCheckpointTruncate folds the WAL back and shrinks the -wal file" {
        TmpCacheRoot.create("silo-wal-").use { root ->
            val dbPath = root.resolve("silo.db")
            val index = SqliteMetadataIndex.open(dbPath)
            repeat(200) { i ->
                index.upsert(CacheKey.requireValid(TestKeys.valid(seed = i.toLong())), sizeBytes = 1024, insertedAtMs = i.toLong())
            }
            val wal = root.resolve("silo.db-wal")
            val walBefore = if (Files.exists(wal)) Files.size(wal) else 0L

            index.walCheckpointTruncate()

            index.checkpointCount shouldBe 1L
            val walAfter = if (Files.exists(wal)) Files.size(wal) else 0L
            walAfter shouldBeLessThanOrEqual walBefore
            walAfter shouldBe 0L
            index.close()
        }
    }

    "vacuum runs and does not grow the database" {
        TmpCacheRoot.create("silo-vacuum-").use { root ->
            val dbPath = root.resolve("silo.db")
            val index = SqliteMetadataIndex.open(dbPath)
            repeat(500) { i ->
                index.upsert(CacheKey.requireValid(TestKeys.valid(seed = i.toLong())), sizeBytes = 4096, insertedAtMs = i.toLong())
            }
            repeat(400) { i -> index.delete(CacheKey.requireValid(TestKeys.valid(seed = i.toLong()))) }
            index.walCheckpointTruncate()
            val sizeBefore = Files.size(dbPath)

            index.vacuum()

            index.vacuumCount shouldBe 1L
            Files.size(dbPath) shouldBeLessThanOrEqual sizeBefore
            index.close()
        }
    }

    "scheduler ticks checkpoints and vacuums on its intervals" {
        TmpCacheRoot.create("silo-sched-").use { root ->
            val index = SqliteMetadataIndex.open(root.resolve("silo.db"))
            index.upsert(CacheKey.requireValid(TestKeys.valid(seed = 1)), sizeBytes = 1024, insertedAtMs = 1)
            val scope = CoroutineScope(Dispatchers.Default)
            val scheduler =
                SqliteMaintenanceScheduler(
                    index = index,
                    checkpointInterval = Duration.ofMillis(40),
                    vacuumInterval = Duration.ofMillis(80),
                )
            val job = scheduler.launchIn(scope)
            try {
                delay(300)
            } finally {
                job.cancel()
            }
            index.checkpointCount shouldBeGreaterThanOrEqual 2L
            index.vacuumCount shouldBeGreaterThanOrEqual 1L
            index.close()
        }
    }
})
