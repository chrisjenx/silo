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
import com.chrisjenx.silo.server.audit.JsonlAuditLog
import com.chrisjenx.silo.server.auth.AuthSettings
import com.chrisjenx.silo.server.auth.PasswordVerifier
import com.chrisjenx.silo.server.auth.Role
import com.chrisjenx.silo.server.auth.User
import com.chrisjenx.silo.server.auth.UserStore
import com.chrisjenx.silo.storage.fs.FileSystemCacheStore
import com.chrisjenx.silo.testing.TmpCacheRoot
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.util.encodeBase64
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset

private const val ADMIN_USER = "admin"
private const val ADMIN_PW = "s3cret"

/** #57: an admin mutation (reconcile) appends an audit entry to the JSONL log. */
class AdminAuditSpec : BehaviorSpec({

    given("audit logging enabled and an authenticated writer") {
        `when`("the writer triggers POST /api/storage/reconcile") {
            then("a JSONL audit entry is appended naming the actor and action") {
                TmpCacheRoot.create("silo-audit-it-").use { root ->
                    val auditDir = root.resolve("audit")
                    val services = buildAuditServices(root, auditDir)
                    testApplication {
                        application { installSiloModule(services) }
                        val resp =
                            client.post("/api/storage/reconcile") {
                                headers {
                                    val tok = "$ADMIN_USER:$ADMIN_PW".toByteArray().encodeBase64()
                                    append(HttpHeaders.Authorization, "Basic $tok")
                                }
                            }
                        resp.status shouldBe HttpStatusCode.OK
                    }
                    val file = auditDir.resolve("audit-${LocalDate.now(ZoneOffset.UTC)}.jsonl")
                    val lines = Files.readAllLines(file)
                    lines shouldHaveSize 1
                    lines[0] shouldContain "\"action\":\"storage.reconcile\""
                    lines[0] shouldContain "\"actor\":\"$ADMIN_USER\""
                    lines[0] shouldContain "\"outcome\":\"ok\""
                    services.metadataIndex.close()
                }
            }
        }
    }
})

private fun buildAuditServices(
    root: Path,
    auditDir: Path,
): SiloServices {
    val config =
        SiloConfig(
            port = 0,
            host = "127.0.0.1",
            storageRoot = root,
            maxEntryBytes = 64L * 1024 * 1024,
            allowUnsupportedFs = false,
            anonymousRead = true,
            auditDir = auditDir,
        )
    val metadataIndex = SqliteMetadataIndex.open(root.resolve("silo.db"))
    val cacheStore = FileSystemCacheStore(root = root, maxEntryBytes = config.maxEntryBytes, fsyncDirOnRename = false)
    val hash = BCrypt.withDefaults().hashToString(4, ADMIN_PW.toCharArray())
    val users = UserStore(listOf(User(ADMIN_USER, hash, setOf(Role.READ, Role.WRITE))))
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
                anonymousRead = true,
                users = users,
                verifier = PasswordVerifier(),
            ),
        auditLog = JsonlAuditLog(auditDir),
    )
}
