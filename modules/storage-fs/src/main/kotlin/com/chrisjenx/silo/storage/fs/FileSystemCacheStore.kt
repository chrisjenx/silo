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
import com.chrisjenx.silo.storage.CacheReadHandle
import com.chrisjenx.silo.storage.CacheStats
import com.chrisjenx.silo.storage.CacheStore
import com.chrisjenx.silo.storage.NoSpaceReason
import com.chrisjenx.silo.storage.PutOutcome
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.RawSource
import kotlinx.io.Source
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * On-disk content-addressed cache store.
 *
 * Layout: every blob lives at `cas/{ab}/{cd}/{key}` rooted at [root]. Writes
 * stream the body into a UUID-suffixed temp file in the *same* leaf shard
 * directory, fsync the file, then rename atomically into place. Concurrent
 * PUTs of the same key cannot corrupt the cache: each writer owns its own
 * temp file and the final rename is atomic, so the rename loser's file is
 * silently overwritten and the cache ends up with a single, valid blob whose
 * bytes equal one of the writers' payloads (last-writer-wins).
 *
 * [maxEntryBytes] is enforced up front from the declared `Content-Length`
 * (Gradle clients always send it). Bodies exceeding the cap short-circuit to
 * [PutOutcome.RejectedTooLarge] without consuming the stream.
 *
 * [fsyncDirOnRename] controls whether the parent shard directory is fsync'd
 * after a successful rename. The default (`true`) is the durable behaviour
 * documented in the bootstrap plan; tests turn it off for speed.
 */
class FileSystemCacheStore(
    private val root: Path,
    private val maxEntryBytes: Long = DEFAULT_MAX_ENTRY_BYTES,
    private val fsyncDirOnRename: Boolean = true,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mover: AtomicMover = DefaultAtomicMover,
    private val metadataIndex: com.chrisjenx.silo.storage.MetadataIndex? = null,
    private val reservedFreeBytes: Long = 0,
) : CacheStore {
    private val log = LoggerFactory.getLogger(FileSystemCacheStore::class.java)
    private val hits = AtomicLong(0)
    private val misses = AtomicLong(0)
    private val puts = AtomicLong(0)
    private val evictions = AtomicLong(0)
    private val crossFsRenames = AtomicLong(0)
    private val enoentDrifts = AtomicLong(0)

    /**
     * Number of times the atomic-rename path fell back to copy+delete because
     * the source and target turned out to be on different filesystems.
     * Surfaced as `silo_storage_cross_fs_rename_total` once the metrics
     * module lands (#12).
     */
    val crossFsRenameCount: Long get() = crossFsRenames.get()

    /**
     * Number of times a GET found a SQLite row for a key whose blob had
     * vanished from disk. Surfaced as
     * `silo_drift_detected_total{kind="missing_blob"}` once metrics land.
     */
    val enoentDriftCount: Long get() = enoentDrifts.get()

    init {
        Files.createDirectories(root.resolve("cas"))
    }

    override suspend fun get(key: CacheKey): CacheReadHandle? =
        withContext(ioDispatcher) {
            val path = ShardLayout.finalPath(root, key)
            if (!Files.exists(path)) {
                // Out-of-band delete? If the metadata index still has the
                // row, this is a drift case — purge the row so future GETs
                // (and reconciliation) are consistent.
                metadataIndex?.let { index ->
                    if (index.get(key) != null) {
                        index.delete(key)
                        enoentDrifts.incrementAndGet()
                        log.warn("ENOENT drift self-heal: purging metadata row for key={}", key.short)
                    }
                }
                misses.incrementAndGet()
                return@withContext null
            }
            hits.incrementAndGet()
            val size = Files.size(path)
            val stream = Files.newInputStream(path, StandardOpenOption.READ)
            FsCacheReadHandle(stream.asSource().buffered(), size, stream)
        }

    override suspend fun put(
        key: CacheKey,
        size: Long,
        body: RawSource,
    ): PutOutcome =
        withContext(ioDispatcher) {
            if (size > maxEntryBytes) {
                return@withContext PutOutcome.RejectedTooLarge(size, maxEntryBytes)
            }
            if (reservedFreeBytes > 0) {
                val usable =
                    try {
                        Files.getFileStore(root).usableSpace
                    } catch (e: IOException) {
                        log.debug("could not query usable space: {}", e.message)
                        Long.MAX_VALUE
                    }
                if (usable - size < reservedFreeBytes) {
                    log.warn(
                        "PUT rejected: reserved-free-bytes threshold tripped (usable={} reserved={} size={})",
                        usable,
                        reservedFreeBytes,
                        size,
                    )
                    return@withContext PutOutcome.NoSpace(NoSpaceReason.RESERVED_FREE_BYTES)
                }
            }
            val shardDir = ShardLayout.shardDir(root, key)
            Files.createDirectories(shardDir)
            val tmp = ShardLayout.tempPath(root, key, UUID.randomUUID().toString())
            val final = ShardLayout.finalPath(root, key)

            try {
                runStreamingPut(tmp, key, size, body)
            } catch (e: IOException) {
                val reason = classifyNoSpace(e) ?: throw e
                Files.deleteIfExists(tmp)
                log.warn("PUT failed with {}: key={} message={}", reason, key.short, e.message)
                return@withContext PutOutcome.NoSpace(reason)
            }

            try {
                mover.move(tmp, final)
            } catch (_: AtomicMoveNotSupportedException) {
                crossFsRenames.incrementAndGet()
                log.warn(
                    "atomic move not supported for {} (source FS={} target FS={}); " +
                        "falling back to copy+delete",
                    key.short,
                    fsTypeOfStore(tmp),
                    fsTypeOfStore(final.parent),
                )
                Files.copy(tmp, final, StandardCopyOption.REPLACE_EXISTING)
                Files.deleteIfExists(tmp)
            }

            if (fsyncDirOnRename) fsyncDirectory(shardDir)

            puts.incrementAndGet()
            PutOutcome.Stored(size)
        }

    override suspend fun has(key: CacheKey): Boolean =
        withContext(ioDispatcher) {
            Files.exists(ShardLayout.finalPath(root, key))
        }

    override suspend fun delete(key: CacheKey): Boolean =
        withContext(ioDispatcher) {
            val removed = Files.deleteIfExists(ShardLayout.finalPath(root, key))
            if (removed) evictions.incrementAndGet()
            removed
        }

    override suspend fun stats(): CacheStats =
        withContext(ioDispatcher) {
            var entryCount = 0L
            var bytesStored = 0L
            val casRoot = root.resolve("cas")
            if (Files.exists(casRoot)) {
                Files.walk(casRoot).use { stream ->
                    stream.forEach { p ->
                        if (Files.isRegularFile(p) && !p.fileName.toString().startsWith("tmp.")) {
                            entryCount += 1
                            bytesStored += Files.size(p)
                        }
                    }
                }
            }
            CacheStats(
                entryCount = entryCount,
                bytesStored = bytesStored,
                hits = hits.get(),
                misses = misses.get(),
                puts = puts.get(),
                evictions = evictions.get(),
            )
        }

    private fun runStreamingPut(
        tmp: Path,
        key: CacheKey,
        size: Long,
        body: RawSource,
    ) {
        var written = 0L
        FileChannel.open(
            tmp,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        ).use { ch ->
            val src: Source = body.buffered()
            val buffer = ByteArray(STREAM_BUFFER_SIZE)
            val nio = java.nio.ByteBuffer.wrap(buffer)
            while (true) {
                val n = src.readAtMostTo(buffer, 0, buffer.size)
                if (n == -1) break
                nio.position(0)
                nio.limit(n)
                while (nio.hasRemaining()) ch.write(nio)
                written += n
            }
            if (written != size) {
                throw IOException(
                    "short write for key=${key.short}: declared=$size actual=$written",
                )
            }
            ch.force(true)
        }
    }

    private fun classifyNoSpace(e: IOException): NoSpaceReason? {
        val msg = e.message?.lowercase() ?: return null
        return when {
            "no space left" in msg || "enospc" in msg -> NoSpaceReason.ENOSPC
            "disk quota" in msg || "edquot" in msg -> NoSpaceReason.EDQUOT
            else -> null
        }
    }

    private fun fsTypeOfStore(path: Path): String =
        try {
            Files.getFileStore(path).type() ?: "unknown"
        } catch (e: IOException) {
            log.debug("could not read fs type for {}: {}", path, e.message)
            "unknown"
        }

    private fun fsyncDirectory(dir: Path) {
        // Best-effort directory fsync. Windows + some filesystems reject this
        // (UnsupportedOperationException or IOException("Is a directory"));
        // those failures are non-fatal — the per-file fsync above is the
        // durability boundary.
        try {
            FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
        } catch (e: IOException) {
            log.debug("directory fsync unsupported for {}: {}", dir, e.message)
        } catch (e: UnsupportedOperationException) {
            log.debug("directory fsync unsupported for {}: {}", dir, e.message)
        }
    }

    companion object {
        const val DEFAULT_MAX_ENTRY_BYTES: Long = 2L * 1024 * 1024 * 1024
        private const val STREAM_BUFFER_SIZE: Int = 64 * 1024
    }
}
