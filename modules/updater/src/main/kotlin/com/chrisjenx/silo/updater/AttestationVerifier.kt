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

/** Verifies a jar's GitHub build-provenance attestation. Throws [AttestationException] on any failure. */
interface AttestationVerifier {
    /**
     * @param jarSha256Hex sha-256 of the downloaded jar (the attestation subject digest)
     * @param bundleJson raw GitHub attestations API response
     * @param expectedRepo "owner/name" whose release workflow must be the signer
     * @param expectedTag release tag, e.g. "v0.2.0"
     */
    fun verify(jarSha256Hex: String, bundleJson: String, expectedRepo: String, expectedTag: String)
}

class AttestationException(message: String, cause: Throwable? = null) : Exception(message, cause)
