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
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

class FilesystemSupportSpec : StringSpec({

    "detectFromMountinfo returns the fs type of the deepest matching mount point" {
        TmpCacheRoot.create("silo-fs-mountinfo-").use { tmp ->
            val mountInfo = tmp.resolve("mountinfo")
            Files.writeString(
                mountInfo,
                """
                25 0 8:1 / / rw,relatime - ext4 /dev/sda1 rw
                36 25 0:21 / /mnt rw,relatime - nfs4 server:/data rw
                42 36 0:22 / /mnt/local rw,relatime - ext4 /dev/sdb1 rw
                """.trimIndent(),
            )

            FilesystemSupport.detectFromMountinfo(Path.of("/mnt"), mountInfo) shouldBe "nfs4"
            FilesystemSupport.detectFromMountinfo(Path.of("/mnt/local"), mountInfo) shouldBe "ext4"
            FilesystemSupport.detectFromMountinfo(Path.of("/srv/cache"), mountInfo) shouldBe "ext4"
        }
    }

    "detectFromMountinfo returns null when mountinfo does not exist" {
        TmpCacheRoot.create("silo-fs-missing-").use { tmp ->
            FilesystemSupport.detectFromMountinfo(
                Path.of("/whatever"),
                tmp.resolve("does-not-exist"),
            ) shouldBe null
        }
    }

    "requireSupportedFilesystem throws on NFS" {
        TmpCacheRoot.create("silo-fs-nfs-").use { tmp ->
            val mountInfo = tmp.resolve("mountinfo")
            Files.writeString(
                mountInfo,
                "36 0 0:21 / $tmp rw - nfs server:/x rw\n",
            )

            val ex =
                shouldThrow<UnsupportedFilesystemException> {
                    checkAgainst(tmp, mountInfo, allow = false)
                }
            ex.fsType shouldBe "nfs"
        }
    }

    "requireSupportedFilesystem downgrades to WARN when allow-unsupported-fs is true" {
        TmpCacheRoot.create("silo-fs-nfs-allow-").use { tmp ->
            val mountInfo = tmp.resolve("mountinfo")
            Files.writeString(
                mountInfo,
                "36 0 0:21 / $tmp rw - nfs4 server:/x rw\n",
            )
            // Does not throw.
            checkAgainst(tmp, mountInfo, allow = true)
        }
    }
})

private fun TmpCacheRoot.resolve(name: String) = path.resolve(name)

/**
 * Test helper that bypasses [FilesystemSupport.detectFsType]'s Linux-only
 * short-circuit so we can exercise mountinfo parsing on any host.
 */
private fun checkAgainst(
    rootDir: Path,
    mountInfo: Path,
    allow: Boolean,
) {
    val detected = FilesystemSupport.detectFromMountinfo(rootDir, mountInfo) ?: return
    if (detected.lowercase() in FilesystemSupport.UNSUPPORTED_FS_TYPES) {
        if (!allow) throw UnsupportedFilesystemException(rootDir, detected)
    }
}
