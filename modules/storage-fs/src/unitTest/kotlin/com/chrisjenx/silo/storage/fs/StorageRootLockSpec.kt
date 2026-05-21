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

import com.chrisjenx.silo.testing.TmpCacheRoot
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class StorageRootLockSpec : StringSpec({

    "acquire on a fresh root creates .silo.lock and writes the PID" {
        TmpCacheRoot.create("silo-lock-").use { root ->
            val lock = StorageRootLock.acquire(root)
            lock.use {
                lock.pid shouldBeGreaterThan 0L
                val raw = Files.readString(root.resolve(".silo.lock")).trim()
                raw.toLong() shouldBe lock.pid
            }
        }
    }

    "a second acquire on the same root throws StorageRootLockedException" {
        TmpCacheRoot.create("silo-lock-double-").use { root ->
            val first = StorageRootLock.acquire(root)
            try {
                val ex =
                    shouldThrow<StorageRootLockedException> {
                        StorageRootLock.acquire(root)
                    }
                ex.holderPid shouldBe first.pid
            } finally {
                first.close()
            }
        }
    }

    "releasing a lock lets the next acquire succeed" {
        TmpCacheRoot.create("silo-lock-release-").use { root ->
            val first = StorageRootLock.acquire(root)
            first.close()

            val second = StorageRootLock.acquire(root)
            second.use {
                second.pid shouldBeGreaterThan 0L
            }
        }
    }

    "close is idempotent" {
        TmpCacheRoot.create("silo-lock-idempotent-").use { root ->
            val lock = StorageRootLock.acquire(root)
            lock.close()
            lock.close() // must not throw
        }
    }
})
