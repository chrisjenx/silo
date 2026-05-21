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
import com.chrisjenx.silo.protocol.CacheKey
import com.chrisjenx.silo.protocol.ContentTypes
import com.chrisjenx.silo.storage.fs.FileSystemCacheStore
import com.chrisjenx.silo.testing.TestKeys
import com.chrisjenx.silo.testing.TmpCacheRoot
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.head
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.io.Buffer
import kotlinx.io.write
import java.nio.file.Path

class CacheHeadSpec : StringSpec({

    "HEAD on a hit returns 200 with Content-Length and Content-Type, empty body" {
        TmpCacheRoot.create("silo-head-hit-").use { root ->
            val services = buildServices(root)
            val key = CacheKey.requireValid(TestKeys.valid(seed = 1))
            val payload = ByteArray(2048) { it.toByte() }
            services.cacheStore.put(key, payload.size.toLong(), Buffer().apply { write(payload) })

            testApplication {
                application { installSiloModule(services) }
                val response = client.head("/cache/${key.value}")
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.ContentLength] shouldBe payload.size.toString()
                response.headers[HttpHeaders.ContentType] shouldBe ContentTypes.CACHE_BODY
                response.bodyAsBytes().size shouldBe 0
            }
            services.metadataIndex.close()
        }
    }

    "HEAD on a miss returns 404" {
        TmpCacheRoot.create("silo-head-miss-").use { root ->
            val services = buildServices(root)
            testApplication {
                application { installSiloModule(services) }
                client.head("/cache/${TestKeys.valid(seed = 999)}").status shouldBe HttpStatusCode.NotFound
            }
            services.metadataIndex.close()
        }
    }

    "HEAD with a malformed key returns 400" {
        TmpCacheRoot.create("silo-head-bad-").use { root ->
            val services = buildServices(root)
            testApplication {
                application { installSiloModule(services) }
                client.head("/cache/not-hex").status shouldBe HttpStatusCode.BadRequest
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
            maxEntryBytes = 64L * 1024 * 1024,
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
