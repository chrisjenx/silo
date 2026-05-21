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

/**
 * The result of a [CacheStore.put]. Modeled as a sealed type so the HTTP
 * layer's `when` is exhaustive and a new outcome cannot be silently ignored.
 */
sealed interface PutOutcome {
    /** The bytes are now committed under the requested key. */
    data class Stored(val sizeBytes: Long) : PutOutcome

    /**
     * The backend detected that an identical-bytes blob was already present
     * (e.g. via a content hash) and skipped the write. From the client's
     * perspective this is still success.
     */
    data class AlreadyPresent(val sizeBytes: Long) : PutOutcome

    /**
     * The declared body size exceeds the configured per-entry cap; the body
     * was rejected without being streamed to durable storage. The HTTP layer
     * maps this to `413 Payload Too Large`.
     */
    data class RejectedTooLarge(val sizeBytes: Long, val maxBytes: Long) : PutOutcome
}
