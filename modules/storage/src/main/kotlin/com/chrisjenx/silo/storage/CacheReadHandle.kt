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

import kotlinx.io.RawSource

/**
 * A handle on a single cache-hit GET. Holds the underlying [RawSource], the
 * exact byte length of the body, and an optional content sha-256 for verify
 * paths.
 *
 * The handle is [AutoCloseable]; closing it releases the file descriptor or
 * other backend resource. Closing the [body] separately is not required —
 * [close] cascades to it.
 */
interface CacheReadHandle : AutoCloseable {
    /** Streaming view over the cached bytes. Read exactly [sizeBytes] bytes. */
    val body: RawSource

    /** Exact length in bytes. The HTTP layer uses this for `Content-Length`. */
    val sizeBytes: Long

    /**
     * SHA-256 of the body, if the backend already has it on hand. `null`
     * means "not computed" — the HTTP layer must not infer corruption from a
     * null value.
     */
    val contentSha256: ByteArray?
}
