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

class SigstoreAttestationVerifierSpec : StringSpec({

    val bundle = SigstoreAttestationVerifierSpec::class.java
        .getResource("/attestation-v0.1.3.json")?.readText()
        ?: error("fixture /attestation-v0.1.3.json missing")
    val digest = SigstoreAttestationVerifierSpec::class.java
        .getResource("/attestation-v0.1.3.sha256")?.readText()?.trim()
        ?: error("fixture /attestation-v0.1.3.sha256 missing")

    "verifies the real provenance bundle for the expected repo + tag" {
        SigstoreAttestationVerifier().verify(digest, bundle, "chrisjenx/silo", "v0.1.3")
        // no exception == pass
    }

    "rejects a mismatched subject digest" {
        shouldThrow<AttestationException> {
            SigstoreAttestationVerifier().verify("00".repeat(32), bundle, "chrisjenx/silo", "v0.1.3")
        }
    }

    "rejects an unexpected signer repo" {
        shouldThrow<AttestationException> {
            SigstoreAttestationVerifier().verify(digest, bundle, "evil/fork", "v0.1.3")
        }
    }
})
