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
import com.chrisjenx.silo.protocol.CacheKey
import com.chrisjenx.silo.server.auth.AuthSettings
import com.chrisjenx.silo.server.auth.PasswordVerifier
import com.chrisjenx.silo.server.auth.Role
import com.chrisjenx.silo.server.auth.User
import com.chrisjenx.silo.server.auth.UserStore
import com.chrisjenx.silo.storage.fs.FileSystemCacheStore
import com.chrisjenx.silo.testing.TestKeys
import com.chrisjenx.silo.testing.TmpCacheRoot
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.util.encodeBase64
import java.nio.file.Path
import kotlin.random.Random

private const val WRITER_USER = "writer"
private const val WRITER_PW = "letmein"

class CachePutSpec : BehaviorSpec({

    given("an authenticated writer") {
        `when`("a valid PUT lands") {
            then("it stores the bytes and a subsequent anonymous GET returns them") {
                TmpCacheRoot.create("silo-put-").use { root ->
                    val services = buildServices(root)
                    val key = CacheKey.requireValid(TestKeys.valid(seed = 1))
                    val payload = ByteArray(8192).also { Random(1).nextBytes(it) }

                    testApplication {
                        application { installSiloModule(services) }
                        val put =
                            client.put("/cache/${key.value}") {
                                withWriterAuth()
                                setBody(payload)
                            }
                        put.status shouldBe HttpStatusCode.OK
                        val get = client.get("/cache/${key.value}")
                        get.status shouldBe HttpStatusCode.OK
                        get.bodyAsBytes().toList() shouldBe payload.toList()
                    }
                    services.metadataIndex.close()
                }
            }
        }

        `when`("a PUT exceeds the per-entry cap") {
            then("the server returns 413 and the blob is not stored") {
                TmpCacheRoot.create("silo-put-413-").use { root ->
                    val services = buildServices(root, capBytes = 16)
                    val key = CacheKey.requireValid(TestKeys.valid(seed = 2))
                    val payload = ByteArray(64)

                    testApplication {
                        application { installSiloModule(services) }
                        val put =
                            client.put("/cache/${key.value}") {
                                withWriterAuth()
                                setBody(payload)
                            }
                        put.status shouldBe HttpStatusCode.PayloadTooLarge
                        client.get("/cache/${key.value}").status shouldBe HttpStatusCode.NotFound
                    }
                    services.metadataIndex.close()
                }
            }
        }

        `when`("a PUT with a malformed key arrives") {
            then("the server returns 400") {
                TmpCacheRoot.create("silo-put-400-").use { root ->
                    val services = buildServices(root)
                    testApplication {
                        application { installSiloModule(services) }
                        val put =
                            client.put("/cache/not-hex") {
                                withWriterAuth()
                                setBody(ByteArray(4))
                            }
                        put.status shouldBe HttpStatusCode.BadRequest
                    }
                    services.metadataIndex.close()
                }
            }
        }
    }

    given("no credentials") {
        `when`("a PUT lands") {
            then("the server returns 401 with a WWW-Authenticate challenge") {
                TmpCacheRoot.create("silo-put-401-").use { root ->
                    val services = buildServices(root)
                    val key = CacheKey.requireValid(TestKeys.valid(seed = 3))
                    testApplication {
                        application { installSiloModule(services) }
                        val put = client.put("/cache/${key.value}") { setBody(ByteArray(4)) }
                        put.status shouldBe HttpStatusCode.Unauthorized
                        put.headers[HttpHeaders.WWWAuthenticate]?.startsWith("Basic") shouldBe true
                    }
                    services.metadataIndex.close()
                }
            }
        }
    }
})

private fun io.ktor.client.request.HttpRequestBuilder.withWriterAuth() {
    val token = "$WRITER_USER:$WRITER_PW".toByteArray(Charsets.UTF_8).encodeBase64()
    headers { append(HttpHeaders.Authorization, "Basic $token") }
}

private fun buildServices(
    root: Path,
    capBytes: Long = 64L * 1024 * 1024,
): SiloServices {
    val config =
        SiloConfig(
            port = 0,
            host = "127.0.0.1",
            storageRoot = root,
            maxEntryBytes = capBytes,
            allowUnsupportedFs = false,
        )
    val metadataIndex = SqliteMetadataIndex.open(root.resolve("silo.db"))
    val cacheStore =
        FileSystemCacheStore(
            root = root,
            maxEntryBytes = capBytes,
            fsyncDirOnRename = false,
        )
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
    )
}
