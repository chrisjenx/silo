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

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

private class FakeSubcommand(override val name: String, private val code: Int) : Subcommand {
    var ranWith: List<String>? = null

    override fun run(args: List<String>): Int {
        ranWith = args
        return code
    }
}

class RunCliSpec : StringSpec({
    "routes a matching subcommand and returns its exit code" {
        val fake = FakeSubcommand("update", 7)
        runCli(arrayOf("update", "--check"), listOf(fake)) shouldBe 7
        fake.ranWith shouldBe listOf("--check")
    }
    "returns null (start server) when nothing matches" {
        runCli(arrayOf(), emptyList()) shouldBe null
    }
    "prints a redirect and returns 1 for a reserved verb that isn't bundled" {
        runCli(arrayOf("update"), emptyList()) shouldBe 1
    }
    "--version returns 0" {
        runCli(arrayOf("--version"), emptyList()) shouldBe 0
    }
})
