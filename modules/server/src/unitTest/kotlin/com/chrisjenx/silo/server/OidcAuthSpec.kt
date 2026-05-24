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
import com.chrisjenx.silo.server.auth.AuthSettings
import com.chrisjenx.silo.server.auth.NimbusTokenVerifier
import com.chrisjenx.silo.server.auth.OidcSettings
import com.chrisjenx.silo.server.auth.PasswordVerifier
import com.chrisjenx.silo.server.auth.TokenVerifier
import com.chrisjenx.silo.server.auth.UserStore
import com.chrisjenx.silo.storage.fs.FileSystemCacheStore
import com.chrisjenx.silo.testing.TestKeys
import com.chrisjenx.silo.testing.TmpCacheRoot
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import java.time.Instant
import java.util.Date

private const val ISSUER = "https://issuer.test"
private const val AUDIENCE = "silo"

private val signingKey: RSAKey = RSAKeyGenerator(2048).keyID("test-key").generate()
private val otherKey: RSAKey = RSAKeyGenerator(2048).keyID("other-key").generate()

private val oidcSettings =
    OidcSettings(
        issuer = ISSUER,
        jwksUrl = "https://issuer.test/jwks",
        audience = AUDIENCE,
        rolesClaim = "roles",
        readRoleValues = setOf("cache:read"),
        writeRoleValues = setOf("cache:write"),
    )

/**
 * Full OIDC / Bearer verification (#54) against an in-memory RSA JWKS — no
 * network. Exercises role mapping, expiry, signature, and issuer checks
 * end-to-end through the cache routes.
 */
class OidcAuthSpec : BehaviorSpec({

    val verifier: TokenVerifier = NimbusTokenVerifier(oidcSettings, ImmutableJWKSet(JWKSet(signingKey.toPublicJWK())))

    given("OIDC enabled and anonymous-read off") {
        `when`("a writer-scoped bearer token PUTs then GETs") {
            then("both succeed") {
                TmpCacheRoot.create("silo-oidc-w-").use { root ->
                    val services = buildOidcServices(root, verifier)
                    val key = CacheKey.requireValid(TestKeys.valid(seed = 1))
                    testApplication {
                        application { installSiloModule(services) }
                        val token = mint(roles = listOf("cache:write"))
                        client.put("/cache/${key.value}") {
                            bearer(token)
                            setBody(ByteArray(128))
                        }.status shouldBe HttpStatusCode.OK
                        client.get("/cache/${key.value}") { bearer(token) }.status shouldBe HttpStatusCode.OK
                    }
                    services.metadataIndex.close()
                }
            }
        }

        `when`("a read-only token attempts a PUT") {
            then("GET works (200) but PUT is forbidden (403)") {
                TmpCacheRoot.create("silo-oidc-r-").use { root ->
                    val services = buildOidcServices(root, verifier)
                    val key = CacheKey.requireValid(TestKeys.valid(seed = 2))
                    testApplication {
                        application { installSiloModule(services) }
                        val token = mint(roles = listOf("cache:read"))
                        client.get("/cache/${key.value}") { bearer(token) }.status shouldBe HttpStatusCode.NotFound
                        client.put("/cache/${key.value}") {
                            bearer(token)
                            setBody(ByteArray(128))
                        }.status shouldBe HttpStatusCode.Forbidden
                    }
                    services.metadataIndex.close()
                }
            }
        }

        `when`("the token is expired") {
            then("the request is unauthorized (401)") {
                TmpCacheRoot.create("silo-oidc-exp-").use { root ->
                    val services = buildOidcServices(root, verifier)
                    val key = CacheKey.requireValid(TestKeys.valid(seed = 3))
                    testApplication {
                        application { installSiloModule(services) }
                        val token = mint(roles = listOf("cache:read"), expiresInSeconds = -60)
                        client.get("/cache/${key.value}") { bearer(token) }.status shouldBe HttpStatusCode.Unauthorized
                    }
                    services.metadataIndex.close()
                }
            }
        }

        `when`("the token is signed by an unknown key") {
            then("the request is unauthorized (401)") {
                TmpCacheRoot.create("silo-oidc-sig-").use { root ->
                    val services = buildOidcServices(root, verifier)
                    val key = CacheKey.requireValid(TestKeys.valid(seed = 4))
                    testApplication {
                        application { installSiloModule(services) }
                        val token = mint(roles = listOf("cache:read"), key = otherKey)
                        client.get("/cache/${key.value}") { bearer(token) }.status shouldBe HttpStatusCode.Unauthorized
                    }
                    services.metadataIndex.close()
                }
            }
        }

        `when`("the token carries the wrong issuer") {
            then("the request is unauthorized (401)") {
                TmpCacheRoot.create("silo-oidc-iss-").use { root ->
                    val services = buildOidcServices(root, verifier)
                    val key = CacheKey.requireValid(TestKeys.valid(seed = 5))
                    testApplication {
                        application { installSiloModule(services) }
                        val token = mint(roles = listOf("cache:read"), issuer = "https://evil.test")
                        client.get("/cache/${key.value}") { bearer(token) }.status shouldBe HttpStatusCode.Unauthorized
                    }
                    services.metadataIndex.close()
                }
            }
        }
    }
})

private fun io.ktor.client.request.HttpRequestBuilder.bearer(token: String) {
    headers { append(HttpHeaders.Authorization, "Bearer $token") }
}

private fun mint(
    roles: List<String>,
    expiresInSeconds: Long = 3600,
    issuer: String = ISSUER,
    key: RSAKey = signingKey,
): String {
    val claims =
        JWTClaimsSet.Builder()
            .subject("service-account")
            .issuer(issuer)
            .audience(AUDIENCE)
            .claim("roles", roles)
            .expirationTime(Date.from(Instant.now().plusSeconds(expiresInSeconds)))
            .build()
    val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.keyID).build(), claims)
    jwt.sign(RSASSASigner(key))
    return jwt.serialize()
}

private fun buildOidcServices(
    root: Path,
    verifier: TokenVerifier,
): SiloServices {
    val config =
        SiloConfig(
            port = 0,
            host = "127.0.0.1",
            storageRoot = root,
            maxEntryBytes = 64L * 1024 * 1024,
            allowUnsupportedFs = false,
            anonymousRead = false,
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
                anonymousRead = false,
                users = UserStore(emptyList()),
                verifier = PasswordVerifier(),
                tokenVerifier = verifier,
            ),
    )
}
