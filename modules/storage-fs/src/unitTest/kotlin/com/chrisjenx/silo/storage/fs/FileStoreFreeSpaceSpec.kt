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
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class FileStoreFreeSpaceSpec : StringSpec({

    "reports a positive usable-bytes reading for a real directory" {
        TmpCacheRoot.create("silo-freespace-").use { path ->
            val probe = FileStoreFreeSpace(path)
            (probe.usableBytes() > 0) shouldBe true
        }
    }

    "reports free inodes as positive (Linux) or unlimited (elsewhere)" {
        TmpCacheRoot.create("silo-freespace-inode-").use { path ->
            val probe = FileStoreFreeSpace(path)
            (probe.freeInodes() > 0) shouldBe true
        }
    }
})
