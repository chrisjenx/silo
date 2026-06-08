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

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

object ChecksumVerifier {
    fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read < 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Returns the hex digest listed for [assetName] in `sha256sum`-format text, or null. */
    fun expectedFor(
        assetName: String,
        checksumsTxt: String,
    ): String? =
        checksumsTxt.lineSequence()
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 2)
                if (parts.size == 2) parts[0] to parts[1].trim() else null
            }
            .firstOrNull { it.second == assetName }
            ?.first

    fun matches(
        file: Path,
        assetName: String,
        checksumsTxt: String,
    ): Boolean {
        val expected = expectedFor(assetName, checksumsTxt) ?: return false
        return expected.equals(sha256(file), ignoreCase = true)
    }
}
