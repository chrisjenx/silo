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
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

class CrossFsRenameSpec : StringSpec({

    "AtomicMoveNotSupported triggers the copy+delete fallback and bumps the counter" {
        TmpCacheRoot.create("silo-fs-xfs-").use { root ->
            val firstAttemptThrew = AtomicBoolean(false)
            val mover =
                AtomicMover { tmp: Path, final: Path ->
                    if (firstAttemptThrew.compareAndSet(false, true)) {
                        throw AtomicMoveNotSupportedException(
                            tmp.toString(),
                            final.toString(),
                            "simulated cross-FS",
                        )
                    }
                    DefaultAtomicMover.move(tmp, final)
                }

            val store =
                FileSystemCacheStore(
                    root = root,
                    fsyncDirOnRename = false,
                    mover = mover,
                )

            val key = CacheKey.requireValid(TestKeys.valid(seed = 1))
            val payload = ByteArray(64) { it.toByte() }
            store.put(key, payload.size.toLong(), Buffer().apply { write(payload) })

            store.crossFsRenameCount shouldBe 1L
            store.has(key) shouldBe true
            val finalPath = ShardLayout.finalPath(root, key)
            Files.readAllBytes(finalPath).toList() shouldBe payload.toList()
            // The temp file the mover initially refused must be cleaned up.
            Files.list(ShardLayout.shardDir(root, key)).use { stream ->
                stream.toList().filter { it.fileName.toString().startsWith("tmp.") } shouldBe emptyList()
            }
        }
    }

    "successive atomic moves leave the counter at zero" {
        TmpCacheRoot.create("silo-fs-xfs-counter-").use { root ->
            val store = FileSystemCacheStore(root = root, fsyncDirOnRename = false)
            val key = CacheKey.requireValid(TestKeys.valid(seed = 2))
            store.put(key, 4, Buffer().apply { write(ByteArray(4)) })
            store.crossFsRenameCount shouldBe 0L
        }
    }
})
