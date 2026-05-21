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
package com.chrisjenx.silo.storage

import com.chrisjenx.silo.protocol.CacheKey
import kotlinx.io.RawSink
import kotlinx.io.RawSource

/**
 * The single port through which the HTTP layer reads and writes cache blobs.
 *
 * Every method is `suspend` so backends can off-load blocking I/O onto
 * `Dispatchers.IO` (or a network coroutine for object stores) without forcing
 * the call site to know about threading. Bodies are streamed via kotlinx-io
 * [RawSource] / [RawSink] — implementations must never buffer the body into a
 * heap-resident `ByteArray`.
 *
 * **Concurrency contract:**
 * - Multiple concurrent calls for *different* keys must not block each other.
 * - Two concurrent PUTs for the *same* key must both terminate and leave the
 *   store with one valid blob whose bytes equal one of the two payloads.
 *   Backends are free to pick which (last-writer-wins is the default).
 * - `delete` while a concurrent `get` is mid-stream must not corrupt the
 *   reader; either the reader sees the full pre-delete body or the deletion
 *   is deferred until the reader closes the handle.
 *
 * **Errors** are surfaced as either typed `PutOutcome` values (expected,
 * non-fatal) or as exceptions (`IOException`, `IllegalStateException`). The
 * HTTP layer translates each into the right status code.
 */
interface CacheStore {
    /**
     * Streams the bytes stored under [key], or returns `null` for a miss. The
     * caller MUST close the returned [CacheReadHandle] when the body has been
     * fully written downstream — failing to close may leak file descriptors
     * on the file-system backend.
     */
    suspend fun get(key: CacheKey): CacheReadHandle?

    /**
     * Streams [size] bytes from [body] into the store under [key]. The
     * implementation reads exactly [size] bytes from [body]; reading fewer
     * is a protocol error (`IOException`).
     *
     * @return [PutOutcome.Stored] on success, [PutOutcome.AlreadyPresent] if
     *   the backend can prove the bytes were already present (e.g. via a
     *   content hash) and skipped the write, or [PutOutcome.RejectedTooLarge]
     *   if [size] exceeds the configured per-entry cap.
     */
    suspend fun put(
        key: CacheKey,
        size: Long,
        body: RawSource,
    ): PutOutcome

    /** True if [key] is currently committed to the store. */
    suspend fun has(key: CacheKey): Boolean

    /**
     * Removes [key] from the store. Returns `true` if an entry was removed,
     * `false` if the key was already absent (still a successful operation
     * from the client's perspective).
     */
    suspend fun delete(key: CacheKey): Boolean

    /** Snapshot of aggregate statistics (cheap; callers may poll). */
    suspend fun stats(): CacheStats
}
