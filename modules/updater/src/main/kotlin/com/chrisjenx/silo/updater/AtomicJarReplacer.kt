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
package com.chrisjenx.silo.updater

import org.slf4j.LoggerFactory
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Replaces a jar in place with an already-verified source file, mirroring the cache write
 * protocol: fsync the incoming bytes, keep a .bak rollback point, then atomic-rename. Falls
 * back to a non-atomic rename dance (Windows/locked file) with rollback on failure.
 */
class AtomicJarReplacer(private val fsync: Boolean = true) {
    private val log = LoggerFactory.getLogger(AtomicJarReplacer::class.java)

    // The inner catch is intentionally broad: on ANY failure of the non-atomic replace we must
    // restore the backup before rethrowing, so the on-disk jar is never left half-written.
    @Suppress("TooGenericExceptionCaught")
    fun replace(
        jar: Path,
        verifiedSource: Path,
    ) {
        if (fsync) forceFile(verifiedSource)
        val backup = jar.resolveSibling("${jar.fileName}.bak")
        Files.copy(jar, backup, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
        try {
            Files.move(
                verifiedSource,
                jar,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            log.warn("Atomic move unsupported ({}); using non-atomic replace with rollback.", e.message)
            try {
                Files.move(verifiedSource, jar, StandardCopyOption.REPLACE_EXISTING)
            } catch (t: Exception) {
                Files.copy(backup, jar, StandardCopyOption.REPLACE_EXISTING)
                throw t
            }
        }
        if (fsync) forceDir(jar.toAbsolutePath().parent)
    }

    /** Restores the jar from its .bak sibling. Returns false if no backup exists. */
    fun rollback(jar: Path): Boolean {
        val backup = jar.resolveSibling("${jar.fileName}.bak")
        if (!Files.isRegularFile(backup)) return false
        Files.copy(backup, jar, StandardCopyOption.REPLACE_EXISTING)
        if (fsync) forceDir(jar.toAbsolutePath().parent)
        return true
    }

    private fun forceFile(path: Path) {
        FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE).use { it.force(true) }
    }

    private fun forceDir(dir: Path?) {
        if (dir == null) return
        runCatching {
            FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
        }.onFailure { log.debug("Directory fsync skipped for {}: {}", dir, it.message) }
    }
}
