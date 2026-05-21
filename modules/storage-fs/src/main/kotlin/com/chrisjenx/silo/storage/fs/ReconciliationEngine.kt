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
import com.chrisjenx.silo.storage.MetadataIndex
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.atomic.AtomicLong

/** Per-kind counter exposing what the sweep observed. */
enum class DriftKind { ORPHAN_BLOB, ORPHAN_ROW, STALE_TMP }

/** Summary returned from one reconcile pass. */
data class ReconcileResult(
    val orphanBlobsReindexed: Int,
    val orphanRowsDeleted: Int,
    val staleTmpDeleted: Int,
) {
    val totalDrift: Int get() = orphanBlobsReindexed + orphanRowsDeleted + staleTmpDeleted
}

/**
 * Reconciles the filesystem against the metadata index.
 *
 * - Orphan blob (file present, no SQLite row): re-insert with the file's
 *   mtime as last-access. Self-heal pattern from bazel-remote.
 * - Orphan row (SQLite row, no file): delete the row.
 * - Stale `.tmp.*` file older than [staleTmpAgeMs]: delete (it's a crashed PUT).
 *
 * The walk yields between [batchSize] entries so it doesn't dominate I/O
 * during high traffic.
 */
class ReconciliationEngine(
    private val root: Path,
    private val index: MetadataIndex,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val staleTmpAgeMs: Long = DEFAULT_STALE_TMP_AGE_MS,
    private val clock: Clock = Clock.systemUTC(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val log = LoggerFactory.getLogger(ReconciliationEngine::class.java)
    private val orphanBlobs = AtomicLong(0)
    private val orphanRows = AtomicLong(0)
    private val staleTmps = AtomicLong(0)

    /** Lifetime counter per drift kind. */
    fun driftDetected(kind: DriftKind): Long =
        when (kind) {
            DriftKind.ORPHAN_BLOB -> orphanBlobs.get()
            DriftKind.ORPHAN_ROW -> orphanRows.get()
            DriftKind.STALE_TMP -> staleTmps.get()
        }

    /** Runs one full pass. Safe to call concurrently with serves. */
    suspend fun reconcile(): ReconcileResult =
        withContext(ioDispatcher) {
            val cas = root.resolve("cas")
            val onDiskKeys = mutableSetOf<String>()
            var orphanBlobsThisRun = 0
            var staleTmpsThisRun = 0
            var processed = 0
            if (Files.exists(cas)) {
                val entries =
                    Files.walk(cas).use { stream ->
                        stream.toList().filter { Files.isRegularFile(it) }
                    }
                for (entry in entries) {
                    val name = entry.fileName.toString()
                    when {
                        name.startsWith("tmp.") -> {
                            if (isStale(entry)) {
                                Files.deleteIfExists(entry)
                                staleTmps.incrementAndGet()
                                staleTmpsThisRun += 1
                            }
                        }

                        CacheKey.parse(name) != null -> {
                            onDiskKeys += name
                        }

                        else -> {
                            log.debug("ignoring unexpected file in cas/: {}", entry)
                        }
                    }
                    processed += 1
                    if (processed % batchSize == 0) yield()
                }
            }

            for (rawKey in onDiskKeys) {
                val key = CacheKey.requireValid(rawKey)
                if (index.get(key) == null) {
                    val path = ShardLayout.finalPath(root, key)
                    val mtime = if (Files.exists(path)) Files.getLastModifiedTime(path).toMillis() else clock.millis()
                    index.upsert(key, sizeBytes = Files.size(path), insertedAtMs = mtime, lastAccessMs = mtime)
                    orphanBlobs.incrementAndGet()
                    orphanBlobsThisRun += 1
                }
            }

            val orphanRowsThisRun = sweepOrphanRows(onDiskKeys)

            val result =
                ReconcileResult(
                    orphanBlobsReindexed = orphanBlobsThisRun,
                    orphanRowsDeleted = orphanRowsThisRun,
                    staleTmpDeleted = staleTmpsThisRun,
                )
            if (result.totalDrift > 0) {
                log.info(
                    "reconcile sweep: orphanBlobs={} orphanRows={} staleTmp={}",
                    result.orphanBlobsReindexed,
                    result.orphanRowsDeleted,
                    result.staleTmpDeleted,
                )
            }
            result
        }

    private suspend fun sweepOrphanRows(onDiskKeys: Set<String>): Int {
        var lastKey: String? = null
        var dropped = 0
        var keepPaging = true
        while (keepPaging) {
            val page = index.pageKeysAfter(lastKey, batchSize)
            for (key in page) {
                if (key.value !in onDiskKeys) {
                    index.delete(key)
                    orphanRows.incrementAndGet()
                    dropped += 1
                }
            }
            keepPaging = page.size >= batchSize
            if (page.isNotEmpty()) lastKey = page.last().value
            if (keepPaging) yield()
        }
        return dropped
    }

    private fun isStale(tmp: Path): Boolean {
        val ageMs = clock.millis() - Files.getLastModifiedTime(tmp).toMillis()
        return ageMs >= staleTmpAgeMs
    }

    companion object {
        const val DEFAULT_BATCH_SIZE: Int = 5_000
        const val DEFAULT_STALE_TMP_AGE_MS: Long = 10 * 60 * 1_000
    }
}
