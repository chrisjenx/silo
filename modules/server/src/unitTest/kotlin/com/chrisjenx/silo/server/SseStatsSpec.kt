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
import com.chrisjenx.silo.server.auth.AuthSettings
import com.chrisjenx.silo.server.auth.PasswordVerifier
import com.chrisjenx.silo.server.auth.UserStore
import com.chrisjenx.silo.storage.fs.FileSystemCacheStore
import com.chrisjenx.silo.testing.TmpCacheRoot
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.readUTF8Line
import java.nio.file.Path

/**
 * SSE live-stats stream (#56). Reads the first frame off `/api/stream/stats`
 * and asserts it is a well-formed single-line `data:` JSON snapshot, then
 * closes the connection (which cancels the server-side emit loop).
 */
class SseStatsSpec : BehaviorSpec({

    given("the SSE stats stream with anonymous read allowed") {
        `when`("a client subscribes") {
            then("it receives a text/event-stream and the first data frame is a JSON snapshot") {
                TmpCacheRoot.create("silo-sse-").use { root ->
                    val services = buildSseServices(root, anonymousRead = true)
                    testApplication {
                        application { installSiloModule(services) }
                        client.prepareGet("/api/stream/stats").execute { resp ->
                            resp.status shouldBe HttpStatusCode.OK
                            resp.headers[HttpHeaders.ContentType] shouldContain "text/event-stream"
                            val frame = resp.bodyAsChannel().readUTF8Line()
                            frame.shouldStartWith("data: ")
                            frame shouldContain "\"entryCount\":"
                            frame shouldContain "\"hitRate\":"
                        }
                    }
                    services.metadataIndex.close()
                }
            }
        }
    }

    given("the SSE stats stream with anonymous read disabled") {
        `when`("an unauthenticated client subscribes") {
            then("it is rejected with 401") {
                TmpCacheRoot.create("silo-sse-401-").use { root ->
                    val services = buildSseServices(root, anonymousRead = false)
                    testApplication {
                        application { installSiloModule(services) }
                        client.prepareGet("/api/stream/stats").execute { resp ->
                            resp.status shouldBe HttpStatusCode.Unauthorized
                        }
                    }
                    services.metadataIndex.close()
                }
            }
        }
    }
})

private fun buildSseServices(
    root: Path,
    anonymousRead: Boolean,
): SiloServices {
    val config =
        SiloConfig(
            port = 0,
            host = "127.0.0.1",
            storageRoot = root,
            maxEntryBytes = 64L * 1024 * 1024,
            allowUnsupportedFs = false,
            anonymousRead = anonymousRead,
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
                anonymousRead = anonymousRead,
                users = UserStore(emptyList()),
                verifier = PasswordVerifier(),
            ),
    )
}
