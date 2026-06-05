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

import dev.sigstore.KeylessVerifier
import dev.sigstore.VerificationOptions
import dev.sigstore.VerificationOptions.CertificateMatcher
import dev.sigstore.bundle.Bundle
import dev.sigstore.strings.StringMatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.StringReader

/**
 * In-JVM verifier of a jar's GitHub build-provenance attestation using sigstore-java.
 *
 * Verification is performed entirely against the public-good Sigstore trust root (Fulcio/Rekor/TUF).
 * For every bundle returned by the GitHub attestations API we require ALL of:
 *  1. the in-toto subject digest equals [jarSha256Hex] (sigstore-java compares the supplied digest
 *     bytes against `subject[].digest.sha256` via `Arrays.equals`);
 *  2. the signer certificate's SAN identity equals the repo's release workflow at the expected tag;
 *  3. the certificate's OIDC issuer is GitHub Actions (`https://token.actions.githubusercontent.com`);
 *  4. the signing certificate chains to the public-good Fulcio root (`sigstorePublicDefaults`).
 *
 * If no bundle satisfies every requirement, an [AttestationException] is thrown.
 */
class SigstoreAttestationVerifier : AttestationVerifier {
    private val json = Json { ignoreUnknownKeys = true }

    // Each bundle is tried in turn; ANY failure (parse, chain, identity, digest) is non-fatal until
    // every bundle is exhausted, so the broad catch is intentional — we surface the last error.
    @Suppress("TooGenericExceptionCaught")
    override fun verify(
        jarSha256Hex: String,
        bundleJson: String,
        expectedRepo: String,
        expectedTag: String,
    ) {
        val bundles = extractBundles(bundleJson)
        if (bundles.isEmpty()) {
            throw AttestationException("No attestation bundles returned for sha256:$jarSha256Hex")
        }

        // (b) signer SAN identity == the repo's release workflow at the expected tag, and
        // (c) issuer == GitHub Actions OIDC. Both are exact-string matches against the leaf cert.
        val expectedSan =
            "https://github.com/$expectedRepo/.github/workflows/release.yml@refs/tags/$expectedTag"
        val certificateMatcher: CertificateMatcher =
            CertificateMatcher.fulcio()
                .subjectAlternativeName(StringMatcher.string(expectedSan))
                .issuer(StringMatcher.string(GITHUB_ACTIONS_OIDC_ISSUER))
                .build()
        val options =
            VerificationOptions.builder()
                .addCertificateMatchers(certificateMatcher)
                .build()

        // (d) chain to the public-good Fulcio/Rekor trust root.
        val verifier = KeylessVerifier.builder().sigstorePublicDefaults().build()

        // (a) subject digest == jarSha256Hex: pass the hex-decoded digest bytes; sigstore-java
        // hex-decodes each subject's sha256 and requires Arrays.equals with these bytes.
        val digestBytes = decodeHex(jarSha256Hex)

        var lastError: Exception? = null
        for (raw in bundles) {
            try {
                val bundle = Bundle.from(StringReader(raw))
                verifier.verify(digestBytes, bundle, options)
                return
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw AttestationException(
            "Provenance verification failed for sha256:$jarSha256Hex " +
                "(expected signer SAN $expectedSan, issuer $GITHUB_ACTIONS_OIDC_ISSUER).",
            lastError,
        )
    }

    private fun decodeHex(hex: String): ByteArray {
        if (hex.isEmpty() || hex.length % 2 != 0) {
            throw AttestationException("Invalid sha-256 hex digest: '$hex'")
        }
        return try {
            hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } catch (e: NumberFormatException) {
            throw AttestationException("Invalid sha-256 hex digest: '$hex'", e)
        }
    }

    private fun extractBundles(responseJson: String): List<String> =
        runCatching {
            val root = json.parseToJsonElement(responseJson).jsonObject
            val attestations = root["attestations"]?.jsonArray ?: return emptyList()
            attestations.map { element ->
                val bundle =
                    element.jsonObject["bundle"] as? JsonObject
                        ?: throw AttestationException("Attestation entry is missing a 'bundle' object")
                bundle.toString()
            }
        }.getOrElse { cause ->
            if (cause is AttestationException) throw cause
            throw AttestationException("Malformed attestations response", cause)
        }

    private companion object {
        const val GITHUB_ACTIONS_OIDC_ISSUER = "https://token.actions.githubusercontent.com"
    }
}
