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
package com.chrisjenx.silo.server

/**
 * Resolved at build time from the Gradle property `silo.version` (and at
 * runtime falls back to "dev" when running outside a packaged build).
 *
 * The commit SHA is read from the optional `SILO_COMMIT` environment
 * variable that the release workflow injects.
 */
object SiloVersion {
    val version: String =
        SiloVersion::class.java.`package`?.implementationVersion
            ?: System.getProperty("silo.version")
            ?: "dev"

    val commit: String = System.getenv("SILO_COMMIT") ?: "unknown"

    fun banner(): String =
        """

        ╔══════════════════════════════════════════════╗
        ║   silo $version (commit $commit)
        ║   cache where you keep your grain
        ╚══════════════════════════════════════════════╝
        """.trimIndent()
}
