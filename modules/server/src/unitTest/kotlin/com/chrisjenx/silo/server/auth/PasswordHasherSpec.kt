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
package com.chrisjenx.silo.server.auth

import at.favre.lib.crypto.bcrypt.BCrypt
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith

/** Hashing side of bcrypt — mirrors [PasswordVerifier] which only verifies. */
class PasswordHasherSpec : StringSpec({

    "produces a hash the verifier accepts" {
        val hash = PasswordHasher.hash("correct horse battery staple".toCharArray())
        BCrypt.verifyer().verify("correct horse battery staple".toCharArray(), hash.toCharArray()).verified shouldBe true
    }

    "rejects a wrong password against the produced hash" {
        val hash = PasswordHasher.hash("right".toCharArray())
        BCrypt.verifyer().verify("wrong".toCharArray(), hash.toCharArray()).verified shouldBe false
    }

    "emits a cost-12 \$2a hash" {
        // The docs and the bundled users.conf examples are all $2a$12$ — keep parity.
        PasswordHasher.hash("anything".toCharArray()) shouldStartWith "\$2a\$12\$"
    }

    "salts: two hashes of the same password differ" {
        val a = PasswordHasher.hash("same".toCharArray())
        val b = PasswordHasher.hash("same".toCharArray())
        (a == b) shouldBe false
    }
})
