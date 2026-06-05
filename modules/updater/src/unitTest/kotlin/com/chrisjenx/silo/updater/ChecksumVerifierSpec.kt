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
import io.kotest.engine.spec.tempfile
import io.kotest.matchers.shouldBe
import java.security.MessageDigest

class ChecksumVerifierSpec : StringSpec({

    fun hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    "computes lowercase hex sha-256 of a file" {
        val f = tempfile()
        f.writeBytes("hello".toByteArray())
        ChecksumVerifier.sha256(f.toPath()) shouldBe hex("hello".toByteArray())
    }

    "extracts the expected digest for a named asset" {
        val checksums = "abc123  silo.jar\ndef456  silo-sbom.cdx.json\n"
        ChecksumVerifier.expectedFor("silo.jar", checksums) shouldBe "abc123"
        ChecksumVerifier.expectedFor("missing.bin", checksums) shouldBe null
    }

    "matches returns true only when the file digest equals the checksums entry" {
        val f = tempfile()
        f.writeBytes("payload".toByteArray())
        val good = "${hex("payload".toByteArray())}  silo.jar\n"
        val bad = "0000  silo.jar\n"
        ChecksumVerifier.matches(f.toPath(), "silo.jar", good) shouldBe true
        ChecksumVerifier.matches(f.toPath(), "silo.jar", bad) shouldBe false
    }
})
