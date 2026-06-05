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

import java.nio.file.Path

/** Abstraction over the GitHub Releases + attestations API. Faked in unit tests. */
interface ReleaseClient {
    /** Latest stable release (or newest including prereleases when [includePrerelease]). */
    fun latest(includePrerelease: Boolean): Release

    /** A specific release by tag, e.g. "v0.2.0". */
    fun byTag(tag: String): Release

    /** Fetches a small text asset (e.g. checksums.txt) in full. */
    fun fetchText(url: String): String

    /** Streams a binary asset to [dest]. */
    fun download(url: String, dest: Path)

    /** Raw GitHub attestation bundle JSON for a jar's `sha256:<hex>` digest. */
    fun attestationBundle(sha256Hex: String): String
}
