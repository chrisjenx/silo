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
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OS-level lock asserting that exactly one Silo process owns a given
 * storage root.
 *
 * Backed by `FileChannel.tryLock()` on `.silo.lock` at the root. The PID of
 * the holding JVM is written into the lock file so a second instance can
 * surface a precise message: `storage root locked by PID 1234`.
 *
 * The lock is released on [close] and (best-effort) on JVM shutdown. Lock
 * files are deleted on graceful release; on `kill -9` the OS-level file
 * lock is dropped automatically by the kernel, and the leftover file is
 * a harmless artefact the next acquire will rewrite.
 */
class StorageRootLock private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
    private val lockPath: Path,
    val pid: Long,
) : AutoCloseable {
    private val released = AtomicBoolean(false)
    private val shutdownHook: Thread = Thread { releaseInternal() }

    init {
        Runtime.getRuntime().addShutdownHook(shutdownHook)
    }

    override fun close() {
        releaseInternal()
        // Shutdown hooks cannot be removed while shutdown is in progress.
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
    }

    private fun releaseInternal() {
        if (!released.compareAndSet(false, true)) return
        runCatching { if (lock.isValid) lock.release() }
        runCatching { channel.close() }
        runCatching { Files.deleteIfExists(lockPath) }
    }

    companion object {
        private val log = LoggerFactory.getLogger(StorageRootLock::class.java)
        private const val LOCK_FILE_NAME = ".silo.lock"

        /**
         * Acquires the lock on [rootDir]. Creates [rootDir] if needed.
         *
         * @throws StorageRootLockedException if another JVM (or same JVM, in
         *   a separate acquire) already holds the file lock.
         */
        fun acquire(rootDir: Path): StorageRootLock {
            Files.createDirectories(rootDir)
            val lockPath = rootDir.resolve(LOCK_FILE_NAME)
            val channel =
                FileChannel.open(
                    lockPath,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE,
                )
            val acquired = tryAcquire(channel, lockPath, rootDir)
            return writePidAndWrap(channel, acquired, lockPath)
        }

        private fun tryAcquire(
            channel: FileChannel,
            lockPath: Path,
            rootDir: Path,
        ): FileLock {
            var conflict: Throwable? = null
            val held: FileLock? =
                try {
                    channel.tryLock()
                } catch (e: OverlappingFileLockException) {
                    conflict = e
                    null
                }
            if (held != null) return held
            channel.close()
            throw StorageRootLockedException(rootDir, readHolderPid(lockPath), conflict)
        }

        private fun writePidAndWrap(
            channel: FileChannel,
            lock: FileLock,
            lockPath: Path,
        ): StorageRootLock {
            val pid = ProcessHandle.current().pid()
            channel.truncate(0)
            channel.position(0)
            channel.write(ByteBuffer.wrap(pid.toString().toByteArray(StandardCharsets.US_ASCII)))
            channel.force(true)
            return StorageRootLock(channel, lock, lockPath, pid)
        }

        private fun readHolderPid(lockPath: Path): Long? =
            try {
                val raw = Files.readString(lockPath, StandardCharsets.US_ASCII).trim()
                raw.toLongOrNull()
            } catch (e: IOException) {
                log.debug("could not read pid from $lockPath: ${e.message}")
                null
            }
    }
}

/** Thrown when [StorageRootLock.acquire] cannot obtain the file lock. */
class StorageRootLockedException(
    rootDir: Path,
    val holderPid: Long?,
    cause: Throwable?,
) : IllegalStateException(
        "storage root locked by PID ${holderPid ?: "unknown"}: $rootDir",
        cause,
    )
