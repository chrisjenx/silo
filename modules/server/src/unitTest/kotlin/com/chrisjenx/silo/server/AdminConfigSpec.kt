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
import com.chrisjenx.silo.storage.fs.ReconciliationEngine
import com.chrisjenx.silo.testing.TmpCacheRoot
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Path

class AdminConfigSpec : StringSpec({

    "GET /api/config exposes the storage + eviction caps the operator configures" {
        TmpCacheRoot.create("silo-admin-config-").use { root ->
            val services = buildConfigServices(root)
            testApplication {
                application { installSiloModule(services) }
                val response = client.get("/api/config")
                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                // The named caps from the request — every cap we configure.
                body shouldContain "\"maxBytes\""
                body shouldContain "\"maxEntryBytes\""
                body shouldContain "\"maxEntries\""
                body shouldContain "\"reservedFreeBytes\""
                body shouldContain "\"reservedFreeInodes\""
                body shouldContain "\"maxAgeDays\""
                body shouldContain "\"maxDeletesPerCycle\""
                // The configured values flow through (not just the keys).
                body shouldContain "123456789"
                body shouldContain "4242"
            }
            services.metadataIndex.close()
        }
    }

    "GET /api/config never leaks security material" {
        TmpCacheRoot.create("silo-admin-config-sec-").use { root ->
            val services = buildConfigServices(root)
            testApplication {
                application { installSiloModule(services) }
                val body = client.get("/api/config").bodyAsText()
                body shouldContain "\"anonymousRead\""
                body shouldNotContain "usersConfPath"
                body shouldNotContain "passwordHash"
                body shouldNotContain "password"
            }
            services.metadataIndex.close()
        }
    }
})

private fun buildConfigServices(root: Path): SiloServices {
    val config =
        SiloConfig(
            port = 0,
            host = "127.0.0.1",
            storageRoot = root,
            maxEntryBytes = 2_147_483_648L,
            allowUnsupportedFs = false,
            maxBytes = 123_456_789L,
            maxEntries = 4242L,
            reservedFreeBytes = 5_368_709_120L,
            reservedFreeInodes = 100_000L,
            maxAgeDays = 30,
            maxDeletesPerCycle = 1_000,
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
            AuthSettings(
                anonymousRead = true,
                users = UserStore(emptyList()),
                verifier = PasswordVerifier(),
            ),
        reconciliationEngine = ReconciliationEngine(root = root, index = metadataIndex),
        meterRegistry = com.chrisjenx.silo.metrics.PrometheusFactory.create("test", "test"),
    )
}
