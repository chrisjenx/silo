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
import com.chrisjenx.silo.metrics.PrometheusFactory
import com.chrisjenx.silo.metrics.bindSilo
import com.chrisjenx.silo.server.audit.JsonlAuditLog
import com.chrisjenx.silo.server.audit.NoopAuditLog
import com.chrisjenx.silo.server.auth.AuthSettings
import com.chrisjenx.silo.server.auth.NimbusTokenVerifier
import com.chrisjenx.silo.server.auth.PasswordVerifier
import com.chrisjenx.silo.server.auth.UserStore
import com.chrisjenx.silo.server.auth.installSiloAuth
import com.chrisjenx.silo.storage.fs.FileSystemCacheStore
import com.chrisjenx.silo.storage.fs.FilesystemSupport
import com.chrisjenx.silo.storage.fs.ReconciliationEngine
import com.chrisjenx.silo.storage.fs.StorageRootLock
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
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
            metadataIndex = metadataIndex,
            verifySha256OnRead = config.verifySha256OnRead,
        )
    val readinessProbe = ReadinessProbe(config.storageRoot, metadataIndex)
    val reconciliationEngine = ReconciliationEngine(root = config.storageRoot, index = metadataIndex)
    val meterRegistry = PrometheusFactory.create(env = "production", instance = "silo")
    val users = config.usersConfPath?.let { UserStore.loadFromFile(it) } ?: UserStore(emptyList())
    val tokenVerifier =
        config.oidc?.let {
            log.info("OIDC bearer auth enabled: issuer={} jwks={}", it.issuer, it.jwksUrl)
            NimbusTokenVerifier.fromJwksUrl(it)
        }
    val auth =
        AuthSettings(
            anonymousRead = config.anonymousRead,
            users = users,
            verifier = PasswordVerifier(),
            tokenVerifier = tokenVerifier,
        )
    val auditLog = buildAuditLog(config)
    installSiloModule(
        SiloServices(
            config = config,
            cacheStore = cacheStore,
            metadataIndex = metadataIndex,
            readinessProbe = readinessProbe,
            storageRootLock = lock,
            auth = auth,
            reconciliationEngine = reconciliationEngine,
            meterRegistry = meterRegistry,
            auditLog = auditLog,
        ),
    )
}

private fun buildAuditLog(config: SiloConfig): com.chrisjenx.silo.server.audit.AuditLog =
    config.auditDir?.let {
        LoggerFactory.getLogger("com.chrisjenx.silo.server.Boot").info("admin audit log enabled: dir={}", it)
        JsonlAuditLog(it)
    } ?: NoopAuditLog

/**
 * Wires Silo routes onto [Application] using [services]. Extracted so
 * tests can hand-build a [SiloServices] (with a tmp root + isolated DB)
 * without going through the production boot path.
 */
private fun Application.installSiloPlugins(services: SiloServices) {
    installSiloAuth(services.auth)
    install(CallId) {
        header(HttpHeaders.XRequestId)
        generate { java.util.UUID.randomUUID().toString() }
        verify { it.isNotBlank() }
        replyToHeader(HttpHeaders.XRequestId)
    }
    install(MicrometerMetrics) {
        registry = services.meterRegistry
    }
    // Bind the silo_* meters (drift, cross-fs renames, JVM) here rather than
    // in module() so the test harness gets them too.
    services.meterRegistry.bindSilo(
        cacheStore = services.cacheStore,
        reconciliationEngine = services.reconciliationEngine,
    )
    install(DefaultHeaders) {
        header("Server", "silo/${SiloVersion.version}")
    }
    install(CallLogging) {
        level = Level.INFO
        // Propagate the request id into MDC so every log line emitted within a
        // request — including across coroutine suspension points — carries it.
        // The logstash encoder serialises MDC into the JSON log automatically.
        callIdMdc("requestId")
        // Only emit prefix + duration + status — never the body.
        format { call -> "[${call.response.status()}] ${call.request.local.method.value} ${call.request.path()}" }
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            LoggerFactory.getLogger("com.chrisjenx.silo.server.errors")
                .error("unhandled error on ${call.request.path()}", cause)
            call.respond(HttpStatusCode.InternalServerError)
        }
        status(HttpStatusCode.NotFound) { call, _ -> respondAdminNotFound(call) }
    }
}

fun Application.installSiloModule(services: SiloServices) {
    installSiloPlugins(services)

    routing {
        get("/health") {
            call.respondText(
                """{"status":"ok","version":"${SiloVersion.version}","commit":"${SiloVersion.commit}"}""",
                contentType = io.ktor.http.ContentType.Application.Json,
                status = HttpStatusCode.OK,
            )
        }
        get("/ready") {
            if (services.readinessProbe.isReady()) {
                call.respondText("ready", status = HttpStatusCode.OK)
            } else {
                call.respondText("not-ready", status = HttpStatusCode.ServiceUnavailable)
            }
        }
        get("/metrics") {
            call.respondText(
                services.meterRegistry.scrape(),
                contentType =
                    io.ktor.http.ContentType.parse(
                        com.chrisjenx.silo.protocol.ContentTypes.PROMETHEUS_TEXT,
                    ),
                status = HttpStatusCode.OK,
            )
        }
        staticResources(remotePath = "/admin", basePackage = "static/admin", index = "index.html")
        cacheRoutes(services.cacheStore, services.auth, services.config.maxEntryBytes)
        adminRoutes(
            reconciliationEngine = services.reconciliationEngine,
            cacheStore = services.cacheStore,
            metadataIndex = services.metadataIndex,
            auth = services.auth,
            storageRoot = services.config.storageRoot,
            config = services.config,
            auditLog = services.auditLog,
        )
    }
}

/** Convenience used by [Path]-typed helpers in this module. */
internal fun pathOf(value: String): Path = java.nio.file.Paths.get(value)

private suspend fun respondAdminNotFound(call: io.ktor.server.application.ApplicationCall) {
    if (!call.request.path().startsWith("/admin")) {
        call.respond(HttpStatusCode.NotFound)
        return
    }
    val body =
        {}.javaClass.classLoader
            .getResourceAsStream("static/admin/404.html")
            ?.bufferedReader()?.use { it.readText() }
    if (body != null) {
        call.respondText(
            body,
            contentType = io.ktor.http.ContentType.Text.Html,
            status = HttpStatusCode.NotFound,
        )
    } else {
        call.respond(HttpStatusCode.NotFound)
    }
}
