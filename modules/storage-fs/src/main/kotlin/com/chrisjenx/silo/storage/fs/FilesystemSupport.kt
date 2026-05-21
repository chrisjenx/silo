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

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * NFS detection + unsupported-filesystem guard.
 *
 * NFS is explicitly out of scope for Silo: `fsync()` semantics are weak,
 * `rename()` is non-atomic over RPC, and the data root's lockfile is not
 * reliable across NFS clients. Users wanting network-shared storage should
 * reach for the S3 backend planned for v0.2.
 *
 * Detection strategy:
 * - Linux: parse `/proc/self/mountinfo`, walk back from the storage root
 *   to find the longest matching mount point, and report its FS type.
 * - macOS / Windows: best-effort — log a WARN that detection is unavailable
 *   on this platform but otherwise proceed.
 *
 * Callers should invoke [requireSupportedFilesystem] before opening the
 * lock file. If the user has set [allowUnsupportedFs] (config key
 * `silo.storage.allow-unsupported-fs`), the check downgrades to a WARN.
 */
object FilesystemSupport {
    private val log = LoggerFactory.getLogger(FilesystemSupport::class.java)

    /** Filesystem types we refuse to start on by default. */
    val UNSUPPORTED_FS_TYPES: Set<String> = setOf("nfs", "nfs3", "nfs4")

    /**
     * Throws [UnsupportedFilesystemException] if [rootDir] resolves to one
     * of [UNSUPPORTED_FS_TYPES] (and [allowUnsupportedFs] is false).
     *
     * On non-Linux hosts this is a no-op besides a one-time WARN.
     */
    fun requireSupportedFilesystem(
        rootDir: Path,
        allowUnsupportedFs: Boolean = false,
    ) {
        val detected = detectFsType(rootDir) ?: return
        if (detected.lowercase() in UNSUPPORTED_FS_TYPES) {
            if (allowUnsupportedFs) {
                log.warn(
                    "storage root {} is on unsupported filesystem '{}' but " +
                        "silo.storage.allow-unsupported-fs=true; proceeding at your own risk",
                    rootDir,
                    detected,
                )
            } else {
                throw UnsupportedFilesystemException(rootDir, detected)
            }
        } else {
            log.debug("storage root {} is on filesystem '{}'", rootDir, detected)
        }
    }

    /**
     * Returns the filesystem type for [rootDir] when it can be determined,
     * or `null` when detection is not supported on this OS.
     */
    fun detectFsType(rootDir: Path): String? {
        if (!System.getProperty("os.name").lowercase().contains("linux")) {
            log.debug("filesystem detection only implemented for Linux; skipping")
            return null
        }
        return detectFromMountinfo(rootDir, Paths.get("/proc/self/mountinfo"))
    }

    /**
     * Visible for testing: parse a `mountinfo`-format file and return the
     * filesystem type of the deepest mount point that contains [rootDir].
     */
    fun detectFromMountinfo(
        rootDir: Path,
        mountInfo: Path,
    ): String? {
        if (!Files.exists(mountInfo)) return null
        val target = rootDir.toAbsolutePath().normalize().toString()
        return Files.readAllLines(mountInfo)
            .asSequence()
            .mapNotNull { parseMountinfoLine(it) }
            .filter { (mountPoint, _) -> mountPoint matchesPath target }
            .maxByOrNull { (mountPoint, _) -> mountPoint.length }
            ?.second
    }

    private infix fun String.matchesPath(target: String): Boolean = this == target || target.startsWith("$this/") || this == "/"

    /**
     * Returns the (mount-point, fs-type) pair from a `/proc/self/mountinfo`
     * line, or `null` if the line is malformed. mountinfo lines look like:
     *
     * `36 35 98:0 /mnt1 /mnt/parent rw,noatime master:1 - ext4 /dev/sda1 rw`
     *
     * The mount point is field #5; the fs type is the field directly after
     * the ` - ` separator.
     */
    private fun parseMountinfoLine(line: String): Pair<String, String>? {
        val sepIdx = line.indexOf(" - ")
        if (sepIdx < 0) return null
        val before = line.substring(0, sepIdx).split(" ")
        val after = line.substring(sepIdx + 3).split(" ")
        return if (before.size >= 5 && after.isNotEmpty()) before[4] to after[0] else null
    }
}

/**
 * Thrown when the storage root sits on a filesystem Silo cannot guarantee
 * its atomic-write invariants on (currently NFS variants).
 */
class UnsupportedFilesystemException(
    rootDir: Path,
    val fsType: String,
) : IllegalStateException(
        "storage root $rootDir is on unsupported filesystem '$fsType' " +
            "(NFS-style filesystems break atomic rename / fsync semantics); " +
            "use a local filesystem or set silo.storage.allow-unsupported-fs=true to override.",
    )
