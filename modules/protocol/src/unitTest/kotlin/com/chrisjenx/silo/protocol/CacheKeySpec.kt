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
package com.chrisjenx.silo.protocol

import com.chrisjenx.silo.testing.TestKeys
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldHaveLength

class CacheKeySpec : StringSpec({

    "accepts lowercase hex of length 8..128" {
        val short = "abcdef01"
        val long = "f".repeat(128)
        CacheKey.parse(short)?.value shouldBe short
        CacheKey.parse(long)?.value shouldBe long
    }

    "rejects keys outside the 8..128 length window" {
        CacheKey.parse(TestKeys.tooShort) shouldBe null
        CacheKey.parse(TestKeys.tooLong) shouldBe null
    }

    "rejects mixed-case hex" {
        CacheKey.parse(TestKeys.mixedCase) shouldBe null
    }

    "rejects non-hex characters" {
        CacheKey.parse(TestKeys.nonHex) shouldBe null
    }

    "rejects path-traversal payloads at the boundary" {
        CacheKey.parse(TestKeys.pathTraversal) shouldBe null
        CacheKey.parse("/etc/passwd") shouldBe null
        CacheKey.parse("abcd1234..") shouldBe null
    }

    "requireValid throws on invalid input" {
        shouldThrow<CacheKeyParseException> {
            CacheKey.requireValid("not-hex")
        }
    }

    "short helper returns the first 12 chars" {
        val key = CacheKey.requireValid("abcdef0123456789abcdef")
        key.short.shouldHaveLength(12)
        key.short shouldBe "abcdef012345"
    }
})
