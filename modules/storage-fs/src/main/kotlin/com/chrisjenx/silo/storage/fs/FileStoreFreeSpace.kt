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

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock

/**
 * [FreeSpace] backed by the real volume under [root]. Readings are cached and
 * refreshed at most once per [refreshMs] so the hot PUT path never stats or
 * forks per request. Free inodes are read via `df -i` on Linux; on any other
 * platform — or on any failure — the reading is [Long.MAX_VALUE] so the guard
 * never blocks on a value it cannot trust.
 *
 * No lock is held across I/O. A benign refresh race merely recomputes volatile
 * fields with the same effective value.
 */
class FileStoreFreeSpace(
    private val root: Path,
    private val clock: Clock = Clock.systemUTC(),
    private val refreshMs: Long = DEFAULT_REFRESH_MS,
) : FreeSpace {
    @Volatile private var cachedBytes: Long = Long.MAX_VALUE

    @Volatile private var cachedInodes: Long = Long.MAX_VALUE

    @Volatile private var lastRefreshMs: Long = Long.MIN_VALUE

    override fun usableBytes(): Long {
        refreshIfStale()
        return cachedBytes
    }

    override fun freeInodes(): Long {
        refreshIfStale()
        return cachedInodes
    }

    private fun refreshIfStale() {
        val now = clock.millis()
        if (now - lastRefreshMs < refreshMs) return
        cachedBytes = runCatching { Files.getFileStore(root).usableSpace }.getOrDefault(Long.MAX_VALUE)
        cachedInodes = readFreeInodes()
        lastRefreshMs = now
    }

    private fun readFreeInodes(): Long {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        if (!os.contains("linux")) return Long.MAX_VALUE
        return runCatching {
            val process =
                ProcessBuilder("df", "-i", "--output=iavail", root.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            output.lineSequence()
                .map { it.trim() }
                .lastOrNull { it.toLongOrNull() != null }
                ?.toLong()
                ?: Long.MAX_VALUE
        }.getOrDefault(Long.MAX_VALUE)
    }

    private companion object {
        const val DEFAULT_REFRESH_MS = 1_000L
    }
}
