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
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe

class SemVerSpec : StringSpec({
    "parses plain and v-prefixed versions" {
        SemVer.parse("v0.1.3") shouldBe SemVer(0, 1, 3)
        SemVer.parse("0.2.0") shouldBe SemVer(0, 2, 0)
    }
    "parses a prerelease suffix" {
        SemVer.parse("1.0.0-rc1") shouldBe SemVer(1, 0, 0, "rc1")
    }
    "orders by major, minor, patch" {
        SemVer.parse("0.2.0") shouldBeGreaterThan SemVer.parse("0.1.9")
        SemVer.parse("1.0.0") shouldBeGreaterThan SemVer.parse("0.9.9")
    }
    "a prerelease is lower than its release" {
        SemVer.parse("1.0.0-rc1") shouldBeLessThan SemVer.parse("1.0.0")
    }
    "rejects garbage" {
        runCatching { SemVer.parse("not-a-version") }.isFailure shouldBe true
    }
    "renders as a human-readable version string" {
        SemVer(0, 1, 3).toString() shouldBe "0.1.3"
        SemVer(1, 0, 0, "rc1").toString() shouldBe "1.0.0-rc1"
    }
})
