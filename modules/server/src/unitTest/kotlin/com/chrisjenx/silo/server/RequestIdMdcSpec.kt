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

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.chrisjenx.silo.metadata.sqlite.SqliteMetadataIndex
import com.chrisjenx.silo.server.auth.AuthSettings
import com.chrisjenx.silo.server.auth.PasswordVerifier
import com.chrisjenx.silo.server.auth.UserStore
import com.chrisjenx.silo.storage.fs.FileSystemCacheStore
import com.chrisjenx.silo.testing.TmpCacheRoot
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Structured-log polish (#58): the CallId plugin assigns a per-request id,
 * surfaced in the `X-Request-ID` response header and propagated into MDC so
 * the request-scoped access log line carries `requestId` (which the logstash
 * encoder serialises into the JSON output).
 */
class RequestIdMdcSpec : BehaviorSpec({

    given("the server with CallId + callIdMdc wired") {
        `when`("a request is served") {
            then("the response carries X-Request-ID and the access log MDC carries requestId") {
                TmpCacheRoot.create("silo-reqid-").use { root ->
                    val services = buildReqIdServices(root)
                    val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
                    val appender = ListAppender<ILoggingEvent>().apply { start() }
                    rootLogger.addAppender(appender)
                    try {
                        testApplication {
                            application { installSiloModule(services) }
                            val resp = client.get("/health")
                            resp.headers[HttpHeaders.XRequestId].shouldNotBeNull()
                        }
                        appender.list.any {
                            !it.mdcPropertyMap["requestId"].isNullOrBlank()
                        }.shouldBeTrue()
                    } finally {
                        rootLogger.detachAppender(appender)
                    }
                    services.metadataIndex.close()
                }
            }
        }
    }
})

private fun buildReqIdServices(root: Path): SiloServices {
    val config =
        SiloConfig(
            port = 0,
            host = "127.0.0.1",
            storageRoot = root,
            maxEntryBytes = 64L * 1024 * 1024,
            allowUnsupportedFs = false,
            anonymousRead = true,
        )
    val metadataIndex = SqliteMetadataIndex.open(root.resolve("silo.db"))
    val cacheStore = FileSystemCacheStore(root = root, maxEntryBytes = config.maxEntryBytes, fsyncDirOnRename = false)
    return SiloServices(
        config = config,
        cacheStore = cacheStore,
        metadataIndex = metadataIndex,
        readinessProbe = ReadinessProbe(root, metadataIndex),
        storageRootLock = null,
        reconciliationEngine = com.chrisjenx.silo.storage.fs.ReconciliationEngine(root = root, index = metadataIndex),
        meterRegistry = com.chrisjenx.silo.metrics.PrometheusFactory.create("test", "test"),
        auth =
            AuthSettings(
                anonymousRead = true,
                users = UserStore(emptyList()),
                verifier = PasswordVerifier(),
            ),
    )
}
