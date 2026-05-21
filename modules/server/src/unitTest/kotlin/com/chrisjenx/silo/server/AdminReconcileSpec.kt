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

import at.favre.lib.crypto.bcrypt.BCrypt
import com.chrisjenx.silo.metadata.sqlite.SqliteMetadataIndex
import com.chrisjenx.silo.server.auth.AuthSettings
import com.chrisjenx.silo.server.auth.PasswordVerifier
import com.chrisjenx.silo.server.auth.Role
import com.chrisjenx.silo.server.auth.User
import com.chrisjenx.silo.server.auth.UserStore
import com.chrisjenx.silo.storage.fs.FileSystemCacheStore
import com.chrisjenx.silo.storage.fs.ReconciliationEngine
import com.chrisjenx.silo.testing.TmpCacheRoot
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.util.encodeBase64
import java.nio.file.Path

private const val WRITER_USER = "writer"
private const val WRITER_PW = "letmein"

class AdminReconcileSpec : StringSpec({

    "POST /api/storage/reconcile requires WRITE auth and returns the drift summary" {
        TmpCacheRoot.create("silo-admin-reconcile-").use { root ->
            val services = buildServices(root)
            testApplication {
                application { installSiloModule(services) }
                val response =
                    client.post("/api/storage/reconcile") {
                        val token = "$WRITER_USER:$WRITER_PW".toByteArray(Charsets.UTF_8).encodeBase64()
                        headers { append(HttpHeaders.Authorization, "Basic $token") }
                    }
                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                body shouldContain "orphanBlobsReindexed"
                body shouldContain "orphanRowsDeleted"
                body shouldContain "staleTmpDeleted"
            }
            services.metadataIndex.close()
        }
    }

    "POST /api/storage/reconcile without credentials returns 401" {
        TmpCacheRoot.create("silo-admin-reconcile-401-").use { root ->
            val services = buildServices(root)
            testApplication {
                application { installSiloModule(services) }
                client.post("/api/storage/reconcile").status shouldBe HttpStatusCode.Unauthorized
            }
            services.metadataIndex.close()
        }
    }
})

private fun buildServices(root: Path): SiloServices {
    val config =
        SiloConfig(
            port = 0,
            host = "127.0.0.1",
            storageRoot = root,
            maxEntryBytes = 4L * 1024 * 1024,
            allowUnsupportedFs = false,
        )
    val metadataIndex = SqliteMetadataIndex.open(root.resolve("silo.db"))
    val cacheStore = FileSystemCacheStore(root = root, fsyncDirOnRename = false)
    val hash = BCrypt.withDefaults().hashToString(4, WRITER_PW.toCharArray())
    val users = UserStore(listOf(User(WRITER_USER, hash, setOf(Role.WRITE, Role.READ))))
    return SiloServices(
        config = config,
        cacheStore = cacheStore,
        metadataIndex = metadataIndex,
        readinessProbe = ReadinessProbe(root, metadataIndex),
        storageRootLock = null,
        auth =
            AuthSettings(
                anonymousRead = true,
                users = users,
                verifier = PasswordVerifier(),
            ),
        reconciliationEngine = ReconciliationEngine(root = root, index = metadataIndex),
        meterRegistry = com.chrisjenx.silo.metrics.PrometheusFactory.create("test", "test"),
    )
}
