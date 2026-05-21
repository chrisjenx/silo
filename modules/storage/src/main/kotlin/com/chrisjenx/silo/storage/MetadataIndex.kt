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

/**
 * Durable metadata layer the [CacheStore] consults for entry state that does
 * not live in the blob itself. The filesystem is authoritative for "does the
 * blob exist on disk"; the metadata index is authoritative for size, age,
 * last-access, and the optional content sha-256.
 *
 * Implementations may be in-memory (tests), SQLite WAL (the default), or a
 * cloud counterpart (S3 object tags, etc.).
 */
interface MetadataIndex : AutoCloseable {
    /**
     * Insert or replace the row for [key]. The metadata index records the
     * size, insertion time, an initial last-access timestamp, and an optional
     * content hash supplied by the backend.
     */
    suspend fun upsert(
        key: CacheKey,
        sizeBytes: Long,
        insertedAtMs: Long,
        lastAccessMs: Long = insertedAtMs,
        contentSha256: ByteArray? = null,
    )

    /**
     * Record an access against [key]. The implementation MAY buffer touches
     * and flush them on a configurable cadence to reduce SQLite write
     * amplification on hot-read workloads.
     */
    suspend fun touch(
        key: CacheKey,
        accessedAtMs: Long,
    )

    /** Returns the stored record for [key], or `null` if not present. */
    suspend fun get(key: CacheKey): EntryRecord?

    /** Removes [key] from the index. Returns `true` if a row was deleted. */
    suspend fun delete(key: CacheKey): Boolean

    /** Aggregate (count, totalBytes) snapshot for `/api/stats` and metrics. */
    suspend fun aggregate(): MetadataAggregate

    /**
     * Returns the [limit] least-recently-accessed entries (oldest first),
     * for the eviction engine to feed to [delete]. Only `COMMITTED` rows
     * are considered.
     */
    suspend fun lruVictims(limit: Int): List<EntryRecord>

    /** Explicitly flush any buffered access-time updates. */
    suspend fun flush()

    /** Closes the underlying database connection. Idempotent. */
    override fun close()
}

/** A single durable record about a cache entry. */
data class EntryRecord(
    val key: CacheKey,
    val sizeBytes: Long,
    val insertedAtMs: Long,
    val lastAccessMs: Long,
    val contentSha256: ByteArray?,
    val status: EntryStatus,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EntryRecord) return false
        return key == other.key &&
            sizeBytes == other.sizeBytes &&
            insertedAtMs == other.insertedAtMs &&
            lastAccessMs == other.lastAccessMs &&
            contentSha256.contentEqualsOrBothNull(other.contentSha256) &&
            status == other.status
    }

    override fun hashCode(): Int {
        var result = key.hashCode()
        result = 31 * result + sizeBytes.hashCode()
        result = 31 * result + insertedAtMs.hashCode()
        result = 31 * result + lastAccessMs.hashCode()
        result = 31 * result + (contentSha256?.contentHashCode() ?: 0)
        result = 31 * result + status.hashCode()
        return result
    }

    private fun ByteArray?.contentEqualsOrBothNull(other: ByteArray?): Boolean =
        when {
            this == null && other == null -> true
            this == null || other == null -> false
            else -> contentEquals(other)
        }
}

/** Two-state lifecycle. Tombstoned rows are pending physical deletion. */
enum class EntryStatus(val code: Int) {
    COMMITTED(1),
    TOMBSTONED(2),
    ;

    companion object {
        fun fromCode(code: Int): EntryStatus = entries.first { it.code == code }
    }
}

/** Aggregate snapshot returned by [MetadataIndex.aggregate]. */
data class MetadataAggregate(
    val entryCount: Long,
    val bytesStored: Long,
)
