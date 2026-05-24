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

import com.chrisjenx.silo.server.audit.AuditEntry
import com.chrisjenx.silo.server.audit.AuditLog
import com.chrisjenx.silo.server.audit.NoopAuditLog
import com.chrisjenx.silo.server.auth.AuthSettings
import com.chrisjenx.silo.server.auth.Role
import com.chrisjenx.silo.server.auth.SiloPrincipal
import com.chrisjenx.silo.server.auth.authenticateSilo
import com.chrisjenx.silo.storage.CacheStore
import com.chrisjenx.silo.storage.MetadataIndex
import com.chrisjenx.silo.storage.fs.ReconciliationEngine
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Admin namespace. Currently exposes the manual reconcile trigger; future
 * admin endpoints (stats, config, etc.) attach to the same route block.
 */
fun Route.adminRoutes(
    reconciliationEngine: ReconciliationEngine,
    cacheStore: CacheStore,
    metadataIndex: MetadataIndex,
    auth: AuthSettings,
    storageRoot: java.nio.file.Path,
    config: SiloConfig,
    auditLog: AuditLog = NoopAuditLog,
) {
    authenticateSilo(auth, optional = true) {
        route("/api/stats") {
            get { call.handleStats(cacheStore, metadataIndex, auth) }
        }
        get("/api/stream/stats") {
            call.streamStats(cacheStore, metadataIndex, auth)
        }
        get("/api/storage") {
            call.handleStorage(metadataIndex, auth, storageRoot)
        }
        get("/api/config") {
            call.handleConfig(auth, config)
        }
    }
    authenticateSilo(auth) {
        route("/api/storage") {
            post("/reconcile") {
                if (!call.requireWrite()) return@post
                val result = reconciliationEngine.reconcile()
                auditLog.record(
                    AuditEntry(
                        timestampMs = System.currentTimeMillis(),
                        actor = call.principal<SiloPrincipal>()?.username ?: "anonymous",
                        action = "storage.reconcile",
                        outcome = "ok",
                        details =
                            mapOf(
                                "orphanBlobsReindexed" to result.orphanBlobsReindexed.toString(),
                                "orphanRowsDeleted" to result.orphanRowsDeleted.toString(),
                                "staleTmpDeleted" to result.staleTmpDeleted.toString(),
                            ),
                    ),
                )
                call.respondText(
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.OK,
                ) {
                    """
                    {
                      "orphanBlobsReindexed": ${result.orphanBlobsReindexed},
                      "orphanRowsDeleted": ${result.orphanRowsDeleted},
                      "staleTmpDeleted": ${result.staleTmpDeleted}
                    }
                    """.trimIndent()
                }
            }
        }
    }
}

private suspend fun ApplicationCall.handleStats(
    cacheStore: CacheStore,
    metadataIndex: MetadataIndex,
    auth: AuthSettings,
) {
    if (!authorizeRead(auth)) return
    respondText(
        text = statsSnapshotJson(cacheStore, metadataIndex),
        contentType = ContentType.Application.Json,
        status = HttpStatusCode.OK,
    )
}

/**
 * Server-Sent Events stream (#56): emits a [statsSnapshotJson] frame once per
 * second until the client disconnects (the write throws / the coroutine is
 * cancelled). Powers live tile updates on the admin dashboard.
 */
private suspend fun ApplicationCall.streamStats(
    cacheStore: CacheStore,
    metadataIndex: MetadataIndex,
    auth: AuthSettings,
    periodMs: Long = 1000L,
) {
    if (!authorizeRead(auth)) return
    response.headers.append(HttpHeaders.CacheControl, "no-cache")
    response.headers.append(HttpHeaders.Connection, "keep-alive")
    respondTextWriter(contentType = ContentType.parse("text/event-stream")) {
        while (currentCoroutineContext().isActive) {
            write("data: ${statsSnapshotJson(cacheStore, metadataIndex)}\n\n")
            flush()
            delay(periodMs)
        }
    }
}

/** Single-line JSON stats snapshot shared by `/api/stats` and the SSE stream. */
private suspend fun statsSnapshotJson(
    cacheStore: CacheStore,
    metadataIndex: MetadataIndex,
): String {
    val stats = cacheStore.stats()
    val aggregate = metadataIndex.aggregate()
    val total = stats.hits + stats.misses
    val hitRate = if (total == 0L) 0.0 else stats.hits.toDouble() / total.toDouble()
    // Single line: the SSE frame is `data: <json>\n\n`, so the payload must
    // not contain newlines or it would be mis-parsed by EventSource.
    return "{" +
        "\"entryCount\":${aggregate.entryCount}," +
        "\"bytesStored\":${aggregate.bytesStored}," +
        "\"hits\":${stats.hits}," +
        "\"misses\":${stats.misses}," +
        "\"puts\":${stats.puts}," +
        "\"evictions\":${stats.evictions}," +
        "\"hitRate\":$hitRate" +
        "}"
}

private suspend fun ApplicationCall.handleStorage(
    metadataIndex: MetadataIndex,
    auth: AuthSettings,
    storageRoot: java.nio.file.Path,
) {
    if (!authorizeRead(auth)) return
    val agg = metadataIndex.aggregate()
    val fileStore =
        try {
            java.nio.file.Files.getFileStore(storageRoot)
        } catch (_: java.io.IOException) {
            null
        }
    val usableSpace = fileStore?.usableSpace ?: -1
    val totalSpace = fileStore?.totalSpace ?: -1
    val fsType = fileStore?.type() ?: "unknown"
    respondText(
        contentType = ContentType.Application.Json,
        status = HttpStatusCode.OK,
    ) {
        """
        {
          "root": "${storageRoot.toAbsolutePath()}",
          "fsType": "$fsType",
          "entryCount": ${agg.entryCount},
          "bytesStored": ${agg.bytesStored},
          "usableSpaceBytes": $usableSpace,
          "totalSpaceBytes": $totalSpace
        }
        """.trimIndent()
    }
}

private suspend fun ApplicationCall.handleConfig(
    auth: AuthSettings,
    config: SiloConfig,
) {
    if (!authorizeRead(auth)) return
    respondText(
        contentType = ContentType.Application.Json,
        status = HttpStatusCode.OK,
    ) {
        """
        {
          "server": { "port": ${config.port}, "host": "${config.host}" },
          "storage": {
            "root": "${config.storageRoot}",
            "maxEntryBytes": ${config.maxEntryBytes},
            "allowUnsupportedFs": ${config.allowUnsupportedFs}
          },
          "auth": {
            "anonymousRead": ${config.anonymousRead},
            "usersConfPath": "${config.usersConfPath ?: ""}"
          }
        }
        """.trimIndent()
    }
}

/**
 * Read-side gate for admin GET endpoints: 401 when credentials are required
 * but absent, 403 when present without READ. Returns false after responding.
 */
private suspend fun ApplicationCall.authorizeRead(auth: AuthSettings): Boolean {
    val principal = principal<SiloPrincipal>()
    return when {
        principal != null && Role.READ in principal.roles -> true
        principal == null && auth.anonymousRead -> true
        principal == null -> {
            respond(HttpStatusCode.Unauthorized)
            false
        }
        else -> {
            respond(HttpStatusCode.Forbidden)
            false
        }
    }
}

private suspend fun ApplicationCall.requireWrite(): Boolean {
    val principal = principal<SiloPrincipal>()
    return if (principal != null && Role.WRITE in principal.roles) {
        true
    } else {
        respond(HttpStatusCode.Forbidden)
        false
    }
}
