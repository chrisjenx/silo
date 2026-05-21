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

import com.chrisjenx.silo.metadata.sqlite.SqliteMetadataIndex
import com.chrisjenx.silo.storage.fs.FileSystemCacheStore
import com.chrisjenx.silo.storage.fs.FilesystemSupport
import com.chrisjenx.silo.storage.fs.StorageRootLock
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.nio.file.Files
import java.nio.file.Path

/**
 * Default Ktor entry point. Pulled in by application.conf via
 * `ktor.application.modules = [ com.chrisjenx.silo.server.ApplicationKt.module ]`.
 */
@Suppress("unused")
fun Application.module() {
    val log = LoggerFactory.getLogger("com.chrisjenx.silo.server.Boot")
    log.info(SiloVersion.banner())

    val config = SiloConfig.load(com.typesafe.config.ConfigFactory.load())
    log.info(
        "starting silo: port={} storageRoot={} maxEntryBytes={}",
        config.port,
        config.storageRoot,
        config.maxEntryBytes,
    )

    FilesystemSupport.requireSupportedFilesystem(
        rootDir = config.storageRoot,
        allowUnsupportedFs = config.allowUnsupportedFs,
    )
    Files.createDirectories(config.storageRoot)
    val lock = StorageRootLock.acquire(config.storageRoot)
    log.info("acquired storage root lock pid={}", lock.pid)

    val metadataIndex = SqliteMetadataIndex.open(config.storageRoot.resolve("silo.db"))
    val cacheStore =
        FileSystemCacheStore(
            root = config.storageRoot,
            maxEntryBytes = config.maxEntryBytes,
        )
    val readinessProbe = ReadinessProbe(config.storageRoot, metadataIndex)
    installSiloModule(
        SiloServices(
            config = config,
            cacheStore = cacheStore,
            metadataIndex = metadataIndex,
            readinessProbe = readinessProbe,
            storageRootLock = lock,
        ),
    )
}

/**
 * Wires Silo routes onto [Application] using [services]. Extracted so
 * tests can hand-build a [SiloServices] (with a tmp root + isolated DB)
 * without going through the production boot path.
 */
fun Application.installSiloModule(services: SiloServices) {
    install(DefaultHeaders) {
        header("Server", "silo/${SiloVersion.version}")
    }
    install(CallLogging) {
        level = Level.INFO
        // Only emit prefix + duration + status — never the body.
        format { call -> "[${call.response.status()}] ${call.request.local.method.value} ${call.request.path()}" }
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            LoggerFactory.getLogger("com.chrisjenx.silo.server.errors")
                .error("unhandled error on ${call.request.path()}", cause)
            call.respond(HttpStatusCode.InternalServerError)
        }
    }

    routing {
        get("/health") {
            call.respondText("ok", status = HttpStatusCode.OK)
        }
        get("/ready") {
            if (services.readinessProbe.isReady()) {
                call.respondText("ready", status = HttpStatusCode.OK)
            } else {
                call.respondText("not-ready", status = HttpStatusCode.ServiceUnavailable)
            }
        }
        cacheRoutes(services.cacheStore)
    }
}

/** Convenience used by [Path]-typed helpers in this module. */
internal fun pathOf(value: String): Path = java.nio.file.Paths.get(value)
