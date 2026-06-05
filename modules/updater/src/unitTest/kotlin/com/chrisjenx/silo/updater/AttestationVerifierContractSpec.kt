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

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec

/** Test double reused by UpdaterSpec. */
class FakeAttestationVerifier(private val fail: Boolean = false) : AttestationVerifier {
    var calls = 0

    override fun verify(
        jarSha256Hex: String,
        bundleJson: String,
        expectedRepo: String,
        expectedTag: String,
    ) {
        calls++
        if (fail) throw AttestationException("forced failure")
    }
}

class AttestationVerifierContractSpec : StringSpec({
    "a failing verifier throws AttestationException" {
        shouldThrow<AttestationException> {
            FakeAttestationVerifier(fail = true).verify("d", "{}", "acme/silo", "v0.2.0")
        }
    }
})
