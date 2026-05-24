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

import com.chrisjenx.silo.server.auth.AuthSettings
import com.chrisjenx.silo.server.auth.Role
import com.chrisjenx.silo.server.auth.SiloPrincipal
import com.chrisjenx.silo.server.auth.authenticateSilo
import com.chrisjenx.silo.storage.CacheStore
import com.chrisjenx.silo.storage.MetadataIndex
import com.chrisjenx.silo.storage.fs.ReconciliationEngine
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

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
) {
    authenticateSilo(auth, optional = true) {
        route("/api/stats") {
            get { call.handleStats(cacheStore, metadataIndex, auth) }
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
    val principal = principal<SiloPrincipal>()
    if (principal == null && !auth.anonymousRead) {
        respond(HttpStatusCode.Unauthorized)
        return
    }
    if (principal != null && Role.READ !in principal.roles) {
        respond(HttpStatusCode.Forbidden)
        return
    }
    val stats = cacheStore.stats()
    val aggregate = metadataIndex.aggregate()
    val hitRate =
        if (stats.hits + stats.misses == 0L) {
            0.0
        } else {
            stats.hits.toDouble() / (stats.hits + stats.misses).toDouble()
        }
    respondText(
        contentType = ContentType.Application.Json,
        status = HttpStatusCode.OK,
    ) {
        """
        {
          "entryCount": ${aggregate.entryCount},
          "bytesStored": ${aggregate.bytesStored},
          "hits": ${stats.hits},
          "misses": ${stats.misses},
          "puts": ${stats.puts},
          "evictions": ${stats.evictions},
          "hitRate": $hitRate
        }
        """.trimIndent()
    }
}

private suspend fun ApplicationCall.handleStorage(
    metadataIndex: MetadataIndex,
    auth: AuthSettings,
    storageRoot: java.nio.file.Path,
) {
    val principal = principal<SiloPrincipal>()
    if (principal == null && !auth.anonymousRead) {
        respond(HttpStatusCode.Unauthorized)
        return
    }
    if (principal != null && Role.READ !in principal.roles) {
        respond(HttpStatusCode.Forbidden)
        return
    }
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
    val principal = principal<SiloPrincipal>()
    if (principal == null && !auth.anonymousRead) {
        respond(HttpStatusCode.Unauthorized)
        return
    }
    if (principal != null && Role.READ !in principal.roles) {
        respond(HttpStatusCode.Forbidden)
        return
    }
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

private suspend fun ApplicationCall.requireWrite(): Boolean {
    val principal = principal<SiloPrincipal>()
    return if (principal != null && Role.WRITE in principal.roles) {
        true
    } else {
        respond(HttpStatusCode.Forbidden)
        false
    }
}
