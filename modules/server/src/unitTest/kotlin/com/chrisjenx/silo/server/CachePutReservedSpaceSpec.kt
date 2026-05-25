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
import com.chrisjenx.silo.storage.fs.FreeSpace
import com.chrisjenx.silo.storage.fs.ReconciliationEngine
import com.chrisjenx.silo.storage.fs.UnlimitedFreeSpace
import com.chrisjenx.silo.testing.TestKeys
import com.chrisjenx.silo.testing.TmpCacheRoot
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.headers
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.util.encodeBase64
import java.nio.file.Path

private const val WRITER = "writer"
private const val WRITER_PW = "letmein"

private val tightSpace =
    object : FreeSpace {
        override fun usableBytes() = 0L

        override fun freeInodes() = Long.MAX_VALUE
    }

class CachePutReservedSpaceSpec : StringSpec({

    "PUT returns 503 when the reserved-free byte threshold would be breached" {
        TmpCacheRoot.create("silo-put-nospace-").use { root ->
            val services = buildServices(root, freeSpace = tightSpace)
            testApplication {
                application { installSiloModule(services) }
                val response =
                    client.put("/cache/${TestKeys.valid(seed = 1L)}") {
                        val token = "$WRITER:$WRITER_PW".toByteArray(Charsets.UTF_8).encodeBase64()
                        headers { append(HttpHeaders.Authorization, "Basic $token") }
                        setBody(ByteArray(1024))
                    }
                response.status shouldBe HttpStatusCode.ServiceUnavailable
            }
            services.metadataIndex.close()
        }
    }

    "PUT succeeds when free space is ample" {
        TmpCacheRoot.create("silo-put-ample-").use { root ->
            val services = buildServices(root, freeSpace = UnlimitedFreeSpace)
            testApplication {
                application { installSiloModule(services) }
                val response =
                    client.put("/cache/${TestKeys.valid(seed = 2L)}") {
                        val token = "$WRITER:$WRITER_PW".toByteArray(Charsets.UTF_8).encodeBase64()
                        headers { append(HttpHeaders.Authorization, "Basic $token") }
                        setBody(ByteArray(1024))
                    }
                response.status shouldBe HttpStatusCode.OK
            }
            services.metadataIndex.close()
        }
    }
})

private fun buildServices(
    root: Path,
    freeSpace: FreeSpace,
): SiloServices {
    val config =
        SiloConfig(
            port = 0,
            host = "127.0.0.1",
            storageRoot = root,
            maxEntryBytes = 4L * 1024 * 1024,
            allowUnsupportedFs = false,
            reservedFreeBytes = 5L * 1024 * 1024 * 1024,
            reservedFreeInodes = 100_000,
        )
    val metadataIndex = SqliteMetadataIndex.open(root.resolve("silo.db"))
    val cacheStore = FileSystemCacheStore(root = root, metadataIndex = metadataIndex, fsyncDirOnRename = false)
    val hash = BCrypt.withDefaults().hashToString(4, WRITER_PW.toCharArray())
    val users = UserStore(listOf(User(WRITER, hash, setOf(Role.WRITE, Role.READ))))
    return SiloServices(
        config = config,
        cacheStore = cacheStore,
        metadataIndex = metadataIndex,
        readinessProbe = ReadinessProbe(root, metadataIndex),
        storageRootLock = null,
        auth = AuthSettings(anonymousRead = true, users = users, verifier = PasswordVerifier()),
        reconciliationEngine = ReconciliationEngine(root = root, index = metadataIndex),
        meterRegistry = com.chrisjenx.silo.metrics.PrometheusFactory.create("test", "test"),
        freeSpace = freeSpace,
    )
}
