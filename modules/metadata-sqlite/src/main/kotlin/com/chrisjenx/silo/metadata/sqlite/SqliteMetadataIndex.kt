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
package com.chrisjenx.silo.metadata.sqlite

import com.chrisjenx.silo.protocol.CacheKey
import com.chrisjenx.silo.storage.EntryRecord
import com.chrisjenx.silo.storage.EntryStatus
import com.chrisjenx.silo.storage.MetadataAggregate
import com.chrisjenx.silo.storage.MetadataIndex
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * SQLite-backed [MetadataIndex] with a single file persisted at [dbPath].
 *
 * The connection is opened in WAL mode and tuned per the bootstrap plan:
 * write amplification on hot reads is collapsed by buffering [touch] calls
 * in memory and flushing them in a single batched UPDATE statement on
 * [flush] (called periodically by the server and explicitly during
 * [close]).
 *
 * The implementation uses plain JDBC — no ORM — to keep the dependency
 * surface small and let us audit every query.
 */
class SqliteMetadataIndex private constructor(
    private val connection: Connection,
    private val ioDispatcher: CoroutineDispatcher,
) : MetadataIndex {
    private val log = LoggerFactory.getLogger(SqliteMetadataIndex::class.java)
    private val touchQueue = ConcurrentHashMap<String, Long>()
    private val writeLock = Mutex()
    private val checkpoints = AtomicLong(0)
    private val vacuums = AtomicLong(0)

    @Volatile
    private var closed = false

    override suspend fun upsert(
        key: CacheKey,
        sizeBytes: Long,
        insertedAtMs: Long,
        lastAccessMs: Long,
        contentSha256: ByteArray?,
    ): Unit =
        withContext(ioDispatcher) {
            writeLock.withLock {
                connection.prepareStatement(SQL_UPSERT).use { ps ->
                    ps.setString(1, key.value)
                    ps.setLong(2, sizeBytes)
                    ps.setLong(3, insertedAtMs)
                    ps.setLong(4, lastAccessMs)
                    if (contentSha256 == null) ps.setNull(5, java.sql.Types.BLOB) else ps.setBytes(5, contentSha256)
                    ps.setInt(6, EntryStatus.COMMITTED.code)
                    ps.executeUpdate()
                }
                touchQueue.remove(key.value)
                Unit
            }
        }

    override suspend fun touch(
        key: CacheKey,
        accessedAtMs: Long,
    ) {
        touchQueue.merge(key.value, accessedAtMs) { prev, next -> if (next > prev) next else prev }
    }

    override suspend fun get(key: CacheKey): EntryRecord? =
        withContext(ioDispatcher) {
            connection.prepareStatement(SQL_SELECT).use { ps ->
                ps.setString(1, key.value)
                ps.executeQuery().use { rs -> if (rs.next()) rs.toRecord() else null }
            }
        }

    override suspend fun delete(key: CacheKey): Boolean =
        withContext(ioDispatcher) {
            writeLock.withLock {
                connection.prepareStatement(SQL_DELETE).use { ps ->
                    ps.setString(1, key.value)
                    touchQueue.remove(key.value)
                    ps.executeUpdate() > 0
                }
            }
        }

    override suspend fun lruVictims(limit: Int): List<EntryRecord> =
        withContext(ioDispatcher) {
            connection.prepareStatement(SQL_LRU).use { ps ->
                ps.setInt(1, EntryStatus.COMMITTED.code)
                ps.setInt(2, limit)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<EntryRecord>()
                    while (rs.next()) out += rs.toRecord()
                    out
                }
            }
        }

    override suspend fun expiredVictims(
        olderThanMs: Long,
        limit: Int,
    ): List<EntryRecord> =
        withContext(ioDispatcher) {
            connection.prepareStatement(SQL_EXPIRED).use { ps ->
                ps.setInt(1, EntryStatus.COMMITTED.code)
                ps.setLong(2, olderThanMs)
                ps.setInt(3, limit)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<EntryRecord>()
                    while (rs.next()) out += rs.toRecord()
                    out
                }
            }
        }

    override suspend fun pageKeysAfter(
        after: String?,
        limit: Int,
    ): List<CacheKey> =
        withContext(ioDispatcher) {
            connection.prepareStatement(SQL_PAGE_KEYS).use { ps ->
                ps.setInt(1, EntryStatus.COMMITTED.code)
                ps.setString(2, after ?: "")
                ps.setInt(3, limit)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<CacheKey>()
                    while (rs.next()) out += CacheKey.requireValid(rs.getString(1))
                    out
                }
            }
        }

    override suspend fun aggregate(): MetadataAggregate =
        withContext(ioDispatcher) {
            connection.prepareStatement(SQL_AGGREGATE).use { ps ->
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        MetadataAggregate(rs.getLong(1), rs.getLong(2))
                    } else {
                        MetadataAggregate(0, 0)
                    }
                }
            }
        }

    override suspend fun flush() =
        withContext(ioDispatcher) {
            writeLock.withLock { flushBlocking() }
        }

    /**
     * Flushes pending touches, then `PRAGMA wal_checkpoint(TRUNCATE)` to fold
     * the WAL back into the main DB and shrink the `-wal` file. Serialized
     * against writers via [writeLock]; safe to call periodically.
     */
    suspend fun walCheckpointTruncate(): Unit =
        withContext(ioDispatcher) {
            writeLock.withLock {
                flushBlocking()
                connection.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") }
                checkpoints.incrementAndGet()
                Unit
            }
        }

    /**
     * Reclaims free pages with `VACUUM`. Cannot run inside a transaction, so
     * it flushes first and runs with autocommit on (the steady state); the
     * [writeLock] keeps it from racing other writers.
     */
    suspend fun vacuum(): Unit =
        withContext(ioDispatcher) {
            writeLock.withLock {
                flushBlocking()
                connection.createStatement().use { it.execute("VACUUM") }
                vacuums.incrementAndGet()
                Unit
            }
        }

    /** Lifetime count of WAL checkpoints run via [walCheckpointTruncate]. */
    val checkpointCount: Long get() = checkpoints.get()

    /** Lifetime count of [vacuum] runs. */
    val vacuumCount: Long get() = vacuums.get()

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        try {
            // close() is not suspend; do a synchronous best-effort flush
            // rather than spinning a coroutine. The writeLock is non-suspend
            // safe to bypass here because no other writer can race a
            // post-close() caller.
            flushBlocking()
        } catch (e: java.sql.SQLException) {
            log.warn("error flushing SqliteMetadataIndex during close", e)
        }
        connection.close()
    }

    private fun flushBlocking() {
        if (touchQueue.isEmpty()) return
        val snapshot = HashMap(touchQueue)
        touchQueue.keys.removeAll(snapshot.keys)
        connection.autoCommit = false
        try {
            connection.prepareStatement(SQL_TOUCH).use { ps ->
                snapshot.forEach { (key, ts) ->
                    ps.setLong(1, ts)
                    ps.setString(2, key)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            connection.commit()
        } catch (e: java.sql.SQLException) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    private fun ResultSet.toRecord(): EntryRecord =
        EntryRecord(
            key = CacheKey.requireValid(getString(1)),
            sizeBytes = getLong(2),
            insertedAtMs = getLong(3),
            lastAccessMs = getLong(4),
            contentSha256 = getBytes(5),
            status = EntryStatus.fromCode(getInt(6)),
        )

    companion object {
        private const val SCHEMA_VERSION = 1

        private val PRAGMAS =
            listOf(
                "PRAGMA journal_mode=WAL",
                "PRAGMA synchronous=NORMAL",
                "PRAGMA busy_timeout=5000",
                "PRAGMA cache_size=-65536",
                "PRAGMA temp_store=MEMORY",
                "PRAGMA foreign_keys=ON",
                "PRAGMA mmap_size=268435456",
            )

        private val SCHEMA_V1_DDL =
            listOf(
                """
                CREATE TABLE IF NOT EXISTS cache_entry (
                    key            TEXT PRIMARY KEY NOT NULL,
                    size_bytes     INTEGER NOT NULL,
                    inserted_at_ms INTEGER NOT NULL,
                    last_access_ms INTEGER NOT NULL,
                    content_sha256 BLOB,
                    status         INTEGER NOT NULL DEFAULT 1
                ) WITHOUT ROWID
                """.trimIndent(),
                "CREATE INDEX IF NOT EXISTS idx_last_access ON cache_entry(last_access_ms)",
                "CREATE INDEX IF NOT EXISTS idx_size ON cache_entry(size_bytes)",
                "CREATE TABLE IF NOT EXISTS schema_version (version INTEGER PRIMARY KEY)",
            )

        private const val SQL_UPSERT = """
            INSERT INTO cache_entry(key, size_bytes, inserted_at_ms, last_access_ms, content_sha256, status)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(key) DO UPDATE SET
                size_bytes     = excluded.size_bytes,
                inserted_at_ms = excluded.inserted_at_ms,
                last_access_ms = excluded.last_access_ms,
                content_sha256 = excluded.content_sha256,
                status         = excluded.status
        """

        private const val SQL_SELECT = """
            SELECT key, size_bytes, inserted_at_ms, last_access_ms, content_sha256, status
            FROM cache_entry WHERE key = ?
        """

        private const val SQL_DELETE = "DELETE FROM cache_entry WHERE key = ?"

        private val SQL_AGGREGATE =
            """
            SELECT COUNT(*), COALESCE(SUM(size_bytes), 0) FROM cache_entry WHERE status = ${EntryStatus.COMMITTED.code}
            """.trimIndent()

        private const val SQL_TOUCH = "UPDATE cache_entry SET last_access_ms = ? WHERE key = ?"

        private val SQL_LRU =
            """
            SELECT key, size_bytes, inserted_at_ms, last_access_ms, content_sha256, status
            FROM cache_entry
            WHERE status = ?
            ORDER BY last_access_ms ASC
            LIMIT ?
            """.trimIndent()

        private val SQL_EXPIRED =
            """
            SELECT key, size_bytes, inserted_at_ms, last_access_ms, content_sha256, status
            FROM cache_entry
            WHERE status = ? AND last_access_ms < ?
            ORDER BY last_access_ms ASC
            LIMIT ?
            """.trimIndent()

        private val SQL_PAGE_KEYS =
            """
            SELECT key FROM cache_entry
            WHERE status = ? AND key > ?
            ORDER BY key ASC
            LIMIT ?
            """.trimIndent()

        /**
         * Opens (or creates) the SQLite database at [path], applies pragmas,
         * runs schema migrations to bring the file up to [SCHEMA_VERSION].
         */
        fun open(
            path: Path,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): SqliteMetadataIndex {
            Files.createDirectories(path.toAbsolutePath().parent)
            val url = "jdbc:sqlite:${path.toAbsolutePath()}"
            val connection = DriverManager.getConnection(url)
            connection.autoCommit = true
            applyPragmas(connection)
            migrate(connection)
            return SqliteMetadataIndex(connection, ioDispatcher)
        }

        private fun applyPragmas(connection: Connection) {
            connection.createStatement().use { stmt ->
                PRAGMAS.forEach { stmt.execute(it) }
            }
        }

        private fun migrate(connection: Connection) {
            connection.autoCommit = false
            try {
                connection.createStatement().use { stmt ->
                    SCHEMA_V1_DDL.forEach { stmt.execute(it) }
                }
                val current = currentVersion(connection)
                if (current < SCHEMA_VERSION) {
                    connection.prepareStatement("INSERT OR REPLACE INTO schema_version(version) VALUES (?)").use { ps ->
                        ps.setInt(1, SCHEMA_VERSION)
                        ps.executeUpdate()
                    }
                }
                connection.commit()
            } catch (e: java.sql.SQLException) {
                connection.rollback()
                throw e
            } finally {
                connection.autoCommit = true
            }
        }

        private fun currentVersion(connection: Connection): Int =
            connection.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version").use { rs ->
                    if (rs.next()) rs.getInt(1) else 0
                }
            }
    }
}
