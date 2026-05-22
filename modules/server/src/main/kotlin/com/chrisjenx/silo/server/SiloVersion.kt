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
 * Version metadata embedded in the fat-jar manifest at build time
 * (`Implementation-Version` and `Implementation-SHA`). Falls back to a
 * system property or the `SILO_COMMIT` env var, then "dev" / "unknown"
 * when running outside a packaged build (tests, `:run`).
 */
object SiloVersion {
    private val manifestAttributes: java.util.jar.Attributes? by lazy {
        runCatching {
            val location =
                SiloVersion::class.java.protectionDomain?.codeSource?.location
                    ?: return@runCatching null
            java.util.jar.JarInputStream(location.openStream()).use { it.manifest?.mainAttributes }
        }.getOrNull()
    }

    val version: String =
        SiloVersion::class.java.`package`?.implementationVersion
            ?: manifestAttributes?.getValue("Implementation-Version")
            ?: System.getProperty("silo.version")
            ?: "dev"

    val commit: String =
        manifestAttributes?.getValue("Implementation-SHA")
            ?: System.getenv("SILO_COMMIT")
            ?: "unknown"

    /** One-line `--version` output: `silo <version> (<sha>) jvm <javaVersion>`. */
    fun line(): String = "silo $version ($commit) jvm ${System.getProperty("java.version")}"

    fun banner(): String =
        """

        ╔══════════════════════════════════════════════╗
        ║   silo $version (commit $commit)
        ║   cache where you keep your grain
        ╚══════════════════════════════════════════════╝
        """.trimIndent()
}
