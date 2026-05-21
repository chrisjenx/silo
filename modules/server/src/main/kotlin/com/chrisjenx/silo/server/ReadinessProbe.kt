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
package com.chrisjenx.silo.server

import com.chrisjenx.silo.storage.MetadataIndex
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.deleteIfExists

/**
 * Snapshot probe used by `/ready`. The constituent checks are cheap and
 * cached only for the duration of one call so a transient I/O blip flips
 * the endpoint immediately.
 */
class ReadinessProbe(
    private val storageRoot: Path,
    private val metadataIndex: MetadataIndex,
) {
    private val log = LoggerFactory.getLogger(ReadinessProbe::class.java)

    /**
     * Returns `true` when the storage root is writable AND the metadata
     * index responds to a trivial read. Either probe failing surfaces as
     * `503 Service Unavailable` on `/ready`.
     */
    suspend fun isReady(): Boolean = storageWritable() && metadataReachable()

    private fun storageWritable(): Boolean {
        val probe = storageRoot.resolve(".ready-${UUID.randomUUID()}")
        return try {
            Files.createDirectories(storageRoot)
            Files.write(probe, byteArrayOf(1))
            true
        } catch (e: java.io.IOException) {
            log.warn("storage root {} not writable: {}", storageRoot, e.message)
            false
        } finally {
            runCatching { probe.deleteIfExists() }
        }
    }

    private suspend fun metadataReachable(): Boolean =
        try {
            metadataIndex.aggregate()
            true
        } catch (e: java.sql.SQLException) {
            log.warn("metadata index not reachable: {}", e.message)
            false
        }
}
