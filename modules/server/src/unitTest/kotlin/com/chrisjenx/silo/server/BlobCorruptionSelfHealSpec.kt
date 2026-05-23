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
import com.chrisjenx.silo.storage.MetadataIndex
import com.chrisjenx.silo.storage.fs.FileSystemCacheStore
import com.chrisjenx.silo.storage.fs.ShardLayout
import com.chrisjenx.silo.testing.TestKeys
import com.chrisjenx.silo.testing.TmpCacheRoot
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.io.Buffer
import kotlinx.io.write
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Chaos test for `verify-sha256-on-read` (#50): when a blob is tampered with
 * on disk (same length, different bytes), a GET with verification enabled
 * detects the SHA-256 mismatch, returns 404, purges the row, and bumps
 * `silo_corruption_detected_total`.
 */
class BlobCorruptionSelfHealSpec : BehaviorSpec({

    given("a stored blob with an indexed SHA-256 and verify-sha256-on-read enabled") {
        `when`("the on-disk blob is overwritten with garbage of the same length") {
            then("GET returns 404, the row is purged, and silo_corruption_detected_total increments") {
                TmpCacheRoot.create("silo-corrupt-").use { root ->
                    val metadataIndex = SqliteMetadataIndex.open(root.resolve("silo.db"))
                    val cacheStore =
                        FileSystemCacheStore(
                            root = root,
                            fsyncDirOnRename = false,
                            metadataIndex = metadataIndex,
                            verifySha256OnRead = true,
                        )
                    val services = buildVerifyServices(root, metadataIndex, cacheStore)

                    val payload = ByteArray(4096).also { Random(3).nextBytes(it) }
                    val sha = MessageDigest.getInstance("SHA-256").digest(payload)
                    val corruptKey = CacheKey.requireValid(TestKeys.valid(seed = 1))
                    val cleanKey = CacheKey.requireValid(TestKeys.valid(seed = 2))
                    listOf(corruptKey, cleanKey).forEach { key ->
                        cacheStore.put(key, payload.size.toLong(), Buffer().apply { write(payload) })
                        metadataIndex.upsert(key, payload.size.toLong(), insertedAtMs = 1_000L, contentSha256 = sha)
                    }

                    // Tamper with one blob: same length, different bytes.
                    val blob = ShardLayout.finalPath(root, corruptKey)
                    Files.write(blob, ByteArray(payload.size).also { Random(99).nextBytes(it) })

                    testApplication {
                        application { installSiloModule(services) }

                        client.get("/cache/${corruptKey.value}").status shouldBe HttpStatusCode.NotFound
                        client.get("/cache/${cleanKey.value}").status shouldBe HttpStatusCode.OK

                        metadataIndex.get(corruptKey) shouldBe null
                        metadataIndex.get(cleanKey).shouldNotBeNull()

                        val scrape = client.get("/metrics").bodyAsText()
                        scrape shouldContain "silo_corruption_detected_total"
                        corruptionDetected(scrape) shouldBe 1.0
                    }

                    metadataIndex.close()
                }
            }
        }
    }
})

private fun corruptionDetected(scrape: String): Double =
    scrape.lineSequence()
        .firstOrNull { it.startsWith("silo_corruption_detected_total") }
        ?.substringAfterLast(' ')
        ?.toDoubleOrNull()
        ?: 0.0

private fun buildVerifyServices(
    root: Path,
    metadataIndex: MetadataIndex,
    cacheStore: FileSystemCacheStore,
): SiloServices {
    val config =
        SiloConfig(
            port = 0,
            host = "127.0.0.1",
            storageRoot = root,
            maxEntryBytes = 64L * 1024 * 1024,
            allowUnsupportedFs = false,
            verifySha256OnRead = true,
        )
    return SiloServices(
        config = config,
        cacheStore = cacheStore,
        metadataIndex = metadataIndex,
        readinessProbe = ReadinessProbe(root, metadataIndex),
        storageRootLock = null,
        reconciliationEngine = com.chrisjenx.silo.storage.fs.ReconciliationEngine(root = root, index = metadataIndex),
        meterRegistry = com.chrisjenx.silo.metrics.PrometheusFactory.create("test", "test"),
        auth =
            com.chrisjenx.silo.server.auth.AuthSettings(
                anonymousRead = true,
                users = com.chrisjenx.silo.server.auth.UserStore(emptyList()),
                verifier = com.chrisjenx.silo.server.auth.PasswordVerifier(),
            ),
    )
}
