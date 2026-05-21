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
import com.chrisjenx.silo.testing.TmpCacheRoot
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Path

class HealthSpec : BehaviorSpec({

    given("an installed Silo module") {
        `when`("/health is requested") {
            then("it returns 200 ok") {
                TmpCacheRoot.create("silo-server-health-").use { root ->
                    val services = buildServices(root)
                    testApplication {
                        application { installSiloModule(services) }
                        val response = client.get("/health")
                        response.status shouldBe HttpStatusCode.OK
                        response.bodyAsText() shouldBe "ok"
                    }
                    services.metadataIndex.close()
                }
            }
        }

        `when`("/ready is requested against a healthy backend") {
            then("it returns 200 ready") {
                TmpCacheRoot.create("silo-server-ready-").use { root ->
                    val services = buildServices(root)
                    testApplication {
                        application { installSiloModule(services) }
                        val response = client.get("/ready")
                        response.status shouldBe HttpStatusCode.OK
                        response.bodyAsText() shouldBe "ready"
                    }
                    services.metadataIndex.close()
                }
            }
        }
    }
})

private fun buildServices(root: Path): SiloServices {
    val config =
        SiloConfig(
            port = 0,
            host = "127.0.0.1",
            storageRoot = root,
            maxEntryBytes = 2L * 1024 * 1024,
            allowUnsupportedFs = false,
        )
    val metadataIndex = SqliteMetadataIndex.open(root.resolve("silo.db"))
    val cacheStore = FileSystemCacheStore(root = root, fsyncDirOnRename = false)
    return SiloServices(
        config = config,
        cacheStore = cacheStore,
        metadataIndex = metadataIndex,
        readinessProbe = ReadinessProbe(root, metadataIndex),
        storageRootLock = null,
        auth =
            com.chrisjenx.silo.server.auth.AuthSettings(
                anonymousRead = true,
                users = com.chrisjenx.silo.server.auth.UserStore(emptyList()),
                verifier = com.chrisjenx.silo.server.auth.PasswordVerifier(),
            ),
    )
}
