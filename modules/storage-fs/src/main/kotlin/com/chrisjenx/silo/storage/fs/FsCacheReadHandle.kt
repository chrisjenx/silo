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

import com.chrisjenx.silo.storage.CacheReadHandle
import kotlinx.io.Source
import java.io.InputStream

/**
 * [CacheReadHandle] wired to a [Source] reading from an open
 * [InputStream]. Closing the handle closes both the buffered source and the
 * underlying stream — failing to call [close] leaks the file descriptor.
 */
internal class FsCacheReadHandle(
    override val body: Source,
    override val sizeBytes: Long,
    private val raw: InputStream,
    override val contentSha256: ByteArray? = null,
) : CacheReadHandle {
    override fun close() {
        try {
            body.close()
        } finally {
            raw.close()
        }
    }
}
