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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant

class StartupRecoverySpec : StringSpec({

    "removes stale tmp files older than the threshold" {
        TmpCacheRoot.create("silo-recovery-").use { root ->
            val key = CacheKey.requireValid(TestKeys.valid(seed = 1))
            val stale = writeTmp(root, key, "old", ageSeconds = 1200)
            val recovery = StartupRecovery(root)

            recovery.cleanOrphanTmp() shouldBe 1
            recovery.orphansCleaned shouldBe 1L
            Files.exists(stale) shouldBe false
        }
    }

    "keeps fresh tmp files within the threshold" {
        TmpCacheRoot.create("silo-recovery-fresh-").use { root ->
            val key = CacheKey.requireValid(TestKeys.valid(seed = 2))
            val fresh = writeTmp(root, key, "new", ageSeconds = 0)
            val recovery = StartupRecovery(root)

            recovery.cleanOrphanTmp() shouldBe 0
            Files.exists(fresh) shouldBe true
        }
    }

    "never touches final blobs" {
        TmpCacheRoot.create("silo-recovery-final-").use { root ->
            val key = CacheKey.requireValid(TestKeys.valid(seed = 3))
            val final = ShardLayout.finalPath(root, key)
            Files.createDirectories(final.parent)
            Files.write(final, ByteArray(16))
            // Backdate it well past the stale threshold to prove tmp-only scope.
            Files.setLastModifiedTime(final, FileTime.from(Instant.now().minusSeconds(1200)))

            StartupRecovery(root).cleanOrphanTmp() shouldBe 0
            Files.exists(final) shouldBe true
        }
    }
})

private fun writeTmp(
    root: Path,
    key: CacheKey,
    uuid: String,
    ageSeconds: Long,
): Path {
    val tmp = ShardLayout.tempPath(root, key, uuid)
    Files.createDirectories(tmp.parent)
    Files.write(tmp, ByteArray(8))
    Files.setLastModifiedTime(tmp, FileTime.from(Instant.now().minusSeconds(ageSeconds)))
    return tmp
}
