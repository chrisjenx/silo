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
package com.chrisjenx.silo.serverupdate

import com.chrisjenx.silo.updater.SemVer
import com.chrisjenx.silo.updater.UpdateOutcome
import com.chrisjenx.silo.updater.UpdateRequest
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path

class UpdateSubcommandSpec : StringSpec({

    fun runWith(
        args: List<String>,
        outcome: UpdateOutcome,
    ): Pair<Int, UpdateRequest> {
        var captured: UpdateRequest? = null
        val code =
            UpdateSubcommand.run(
                args = args,
                currentVersion = "0.1.3",
                repoDefault = "chrisjenx/silo",
                envRepo = null,
                envToken = null,
                confirm = { true },
            ) { request ->
                captured = request
                outcome
            }
        return code to (captured ?: error("request not captured"))
    }

    "name is 'update'" {
        UpdateSubcommand.name shouldBe "update"
    }

    "--check available maps to exit code 10" {
        val (code, req) = runWith(listOf("--check"), UpdateOutcome.UpdateAvailable(SemVer(0, 1, 3), SemVer(0, 2, 0)))
        code shouldBe 10
        req.checkOnly shouldBe true
    }

    "up to date maps to exit code 0" {
        val (code, _) = runWith(listOf("--check"), UpdateOutcome.UpToDate(SemVer(0, 1, 3)))
        code shouldBe 0
    }

    "updated maps to exit code 0" {
        val (code, _) = runWith(emptyList(), UpdateOutcome.Updated(SemVer(0, 1, 3), SemVer(0, 2, 0), Path.of("/x/silo.jar")))
        code shouldBe 0
    }

    "failure maps to exit code 1" {
        val (code, _) = runWith(emptyList(), UpdateOutcome.Failed("boom"))
        code shouldBe 1
    }

    "flags populate the request" {
        val (_, req) =
            runWith(
                listOf("--to", "v0.3.0", "--yes", "--prerelease", "--no-verify-attestation"),
                UpdateOutcome.Updated(SemVer(0, 1, 3), SemVer(0, 3, 0), Path.of("/x/silo.jar")),
            )
        req.toTag shouldBe "v0.3.0"
        req.includePrerelease shouldBe true
        req.verifyAttestation shouldBe false
    }
})
