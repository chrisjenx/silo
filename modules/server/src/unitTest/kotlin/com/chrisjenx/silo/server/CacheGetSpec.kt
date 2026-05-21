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
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.io.Buffer
import kotlinx.io.write
import java.nio.file.Path
import kotlin.random.Random

class CacheGetSpec : BehaviorSpec({

    given("a cache with one stored blob") {
        `when`("the blob is fetched by its key") {
            then("the response is 200 with the exact bytes and content-type") {
                TmpCacheRoot.create("silo-get-").use { root ->
                    val services = buildServices(root)
                    val key = CacheKey.requireValid(TestKeys.valid(seed = 1))
                    val payload = ByteArray(4096).also { Random(1).nextBytes(it) }
                    services.cacheStore.put(key, payload.size.toLong(), Buffer().apply { write(payload) })

                    testApplication {
                        application { installSiloModule(services) }
                        val response = client.get("/cache/${key.value}")
                        response.status shouldBe HttpStatusCode.OK
                        response.headers[HttpHeaders.ContentLength] shouldBe payload.size.toString()
                        response.headers[HttpHeaders.ContentType] shouldBe
                            ContentType.parse(ContentTypes.CACHE_BODY).toString()
                        response.bodyAsBytes().toList() shouldBe payload.toList()
                    }
                    services.metadataIndex.close()
                }
            }
        }

        `when`("an absent key is requested") {
            then("the response is 404") {
                TmpCacheRoot.create("silo-get-miss-").use { root ->
                    val services = buildServices(root)
                    testApplication {
                        application { installSiloModule(services) }
                        val response = client.get("/cache/${TestKeys.valid(seed = 999)}")
                        response.status shouldBe HttpStatusCode.NotFound
                    }
                    services.metadataIndex.close()
                }
            }
        }

        `when`("a malformed key is requested") {
            then("the response is 400") {
                TmpCacheRoot.create("silo-get-bad-").use { root ->
                    val services = buildServices(root)
                    testApplication {
                        application { installSiloModule(services) }
                        val response = client.get("/cache/not-hex")
                        response.status shouldBe HttpStatusCode.BadRequest
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
        reconciliationEngine = com.chrisjenx.silo.storage.fs.ReconciliationEngine(root = root, index = metadataIndex),
        auth =
            com.chrisjenx.silo.server.auth.AuthSettings(
                anonymousRead = true,
                users = com.chrisjenx.silo.server.auth.UserStore(emptyList()),
                verifier = com.chrisjenx.silo.server.auth.PasswordVerifier(),
            ),
    )
}
