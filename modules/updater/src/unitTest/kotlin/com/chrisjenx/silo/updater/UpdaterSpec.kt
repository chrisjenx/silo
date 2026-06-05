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
package com.chrisjenx.silo.updater

import io.kotest.core.spec.style.StringSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

private fun sha256Hex(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Fake serving a single release whose silo.jar is [newJarBytes]. */
private class FakeReleaseClient(
    private val tag: String,
    private val prerelease: Boolean,
    private val newJarBytes: ByteArray,
) : ReleaseClient {
    override fun latest(includePrerelease: Boolean) =
        Release(
            tag,
            SemVer.parse(tag),
            prerelease,
            listOf(
                ReleaseAsset("silo.jar", "mem://silo.jar"),
                ReleaseAsset("checksums.txt", "mem://checksums.txt"),
            ),
        )

    override fun byTag(tag: String) = latest(true)

    override fun fetchText(url: String) = "${sha256Hex(newJarBytes)}  silo.jar\n"

    override fun download(
        url: String,
        dest: Path,
    ) {
        Files.write(dest, newJarBytes)
    }

    override fun attestationBundle(sha256Hex: String) = """{"attestations":[{"bundle":{}}]}"""
}

class UpdaterSpec : StringSpec({

    fun install(version: String): Path {
        val dir = tempdir().toPath()
        return dir.resolve("silo.jar").also { Files.writeString(it, "OLD-$version") }
    }

    fun updater(
        client: ReleaseClient,
        attestOk: Boolean = true,
    ) = Updater(
        releaseClient = client,
        attestationVerifier = FakeAttestationVerifier(fail = !attestOk),
        replacer = AtomicJarReplacer(fsync = false),
        repo = "acme/silo",
    )

    "reports UpToDate when the latest equals the current version" {
        val jar = install("0.2.0")
        val client = FakeReleaseClient("v0.2.0", false, "NEW".toByteArray())
        val outcome = updater(client).run(UpdateRequest(currentVersion = "0.2.0"), jarOverride = jar)
        outcome.shouldBeInstanceOf<UpdateOutcome.UpToDate>()
        Files.readString(jar) shouldBe "OLD-0.2.0"
    }

    "check-only reports UpdateAvailable without writing" {
        val jar = install("0.1.3")
        val client = FakeReleaseClient("v0.2.0", false, "NEW".toByteArray())
        val outcome = updater(client).run(UpdateRequest("0.1.3", checkOnly = true), jarOverride = jar)
        outcome.shouldBeInstanceOf<UpdateOutcome.UpdateAvailable>()
        Files.readString(jar) shouldBe "OLD-0.1.3"
    }

    "happy path downloads, verifies, swaps, and reports Updated" {
        val jar = install("0.1.3")
        val newBytes = "NEW-JAR".toByteArray()
        val outcome =
            updater(FakeReleaseClient("v0.2.0", false, newBytes)).run(
                UpdateRequest("0.1.3"),
                jarOverride = jar,
            )
        outcome.shouldBeInstanceOf<UpdateOutcome.Updated>()
        Files.readAllBytes(jar) shouldBe newBytes
        Files.exists(jar.resolveSibling("silo.jar.bak")) shouldBe true
    }

    "aborts without swapping when the checksum does not match" {
        val jar = install("0.1.3")
        val lying =
            object : ReleaseClient by FakeReleaseClient("v0.2.0", false, "NEW".toByteArray()) {
                override fun fetchText(url: String) = "deadbeef  silo.jar\n"
            }
        val outcome = updater(lying).run(UpdateRequest("0.1.3"), jarOverride = jar)
        outcome.shouldBeInstanceOf<UpdateOutcome.Failed>()
        Files.readString(jar) shouldBe "OLD-0.1.3"
    }

    "aborts without swapping when attestation verification fails" {
        val jar = install("0.1.3")
        val outcome =
            updater(FakeReleaseClient("v0.2.0", false, "NEW".toByteArray()), attestOk = false)
                .run(UpdateRequest("0.1.3"), jarOverride = jar)
        outcome.shouldBeInstanceOf<UpdateOutcome.Failed>()
        Files.readString(jar) shouldBe "OLD-0.1.3"
    }

    "skips attestation when verifyAttestation = false but still enforces checksum" {
        val jar = install("0.1.3")
        val outcome =
            updater(FakeReleaseClient("v0.2.0", false, "NEW".toByteArray()), attestOk = false)
                .run(UpdateRequest("0.1.3", verifyAttestation = false), jarOverride = jar)
        outcome.shouldBeInstanceOf<UpdateOutcome.Updated>()
    }

    "rollback restores the previous jar" {
        val jar = install("0.1.3")
        updater(FakeReleaseClient("v0.2.0", false, "NEW".toByteArray())).run(UpdateRequest("0.1.3"), jarOverride = jar)
        val outcome =
            updater(FakeReleaseClient("v0.2.0", false, "NEW".toByteArray()))
                .run(UpdateRequest("0.2.0", rollback = true), jarOverride = jar)
        outcome.shouldBeInstanceOf<UpdateOutcome.RolledBack>()
        Files.readString(jar) shouldBe "OLD-0.1.3"
    }
})
