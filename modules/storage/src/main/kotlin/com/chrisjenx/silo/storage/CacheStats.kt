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
 * Aggregate counters published by a [CacheStore]. Producers update fields
 * atomically; consumers see a consistent snapshot.
 */
data class CacheStats(
    val entryCount: Long,
    val bytesStored: Long,
    val hits: Long,
    val misses: Long,
    val puts: Long,
    val evictions: Long,
) {
    companion object {
        val EMPTY: CacheStats =
            CacheStats(
                entryCount = 0,
                bytesStored = 0,
                hits = 0,
                misses = 0,
                puts = 0,
                evictions = 0,
            )
    }
}
