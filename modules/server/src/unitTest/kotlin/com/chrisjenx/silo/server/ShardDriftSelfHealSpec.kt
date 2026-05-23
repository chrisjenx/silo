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
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.random.Random

/**
 * End-to-end chaos test for the ENOENT drift self-heal path (#49): an
 * external `rm -rf cas/{ab}` deletes a whole top-level shard out of band.
 * The next GET for an affected key must self-heal — purge the SQLite row,
 * return 404 (a cache miss to the client), and bump
 * `silo_drift_detected_total{kind="missing_blob"}`.
 */
class ShardDriftSelfHealSpec : BehaviorSpec({

    given("100 stored keys with on-disk blobs and metadata rows") {
        `when`("a cas/{ab} shard directory is deleted out of band") {
            then("affected GETs return 404, their rows are purged, and the drift metric increments") {
                TmpCacheRoot.create("silo-drift-").use { root ->
                    val metadataIndex = SqliteMetadataIndex.open(root.resolve("silo.db"))
                    val cacheStore =
                        FileSystemCacheStore(
                            root = root,
                            fsyncDirOnRename = false,
                            metadataIndex = metadataIndex,
                        )
                    val services = buildDriftServices(root, metadataIndex, cacheStore)

                    val payload = ByteArray(256).also { Random(7).nextBytes(it) }
                    val keys = TestKeys.sequence(100).map { CacheKey.requireValid(it) }
                    keys.forEach { key ->
                        cacheStore.put(key, payload.size.toLong(), Buffer().apply { write(payload) })
                        metadataIndex.upsert(key, payload.size.toLong(), insertedAtMs = 1_000L)
                    }

                    // Wipe one whole top-level shard: cas/{ab}.
                    val shardPrefix = keys.first().value.substring(0, 2)
                    val affected = keys.filter { it.value.startsWith(shardPrefix) }
                    val survivor = keys.first { !it.value.startsWith(shardPrefix) }
                    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
                    root.resolve("cas").resolve(shardPrefix).deleteRecursively()

                    testApplication {
                        application { installSiloModule(services) }

                        affected.forEach { key ->
                            client.get("/cache/${key.value}").status shouldBe HttpStatusCode.NotFound
                        }
                        client.get("/cache/${survivor.value}").status shouldBe HttpStatusCode.OK

                        affected.forEach { key -> metadataIndex.get(key) shouldBe null }
                        metadataIndex.get(survivor).shouldNotBeNull()

                        val scrape = client.get("/metrics").bodyAsText()
                        scrape shouldContain "silo_drift_detected_total"
                        driftDetected(scrape) shouldBe affected.size.toDouble()
                    }

                    metadataIndex.close()
                }
            }
        }
    }
})

/** Parses `silo_drift_detected_total{kind="missing_blob"} <value>` from a Prometheus scrape. */
private fun driftDetected(scrape: String): Double =
    scrape.lineSequence()
        .firstOrNull { it.startsWith("silo_drift_detected_total") && it.contains("missing_blob") }
        ?.substringAfterLast(' ')
        ?.toDoubleOrNull()
        ?: 0.0

private fun buildDriftServices(
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
