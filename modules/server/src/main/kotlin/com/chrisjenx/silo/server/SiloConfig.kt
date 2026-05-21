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

import com.typesafe.config.Config
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Parsed Silo configuration. Plain data class — no Ktor or HOCON types
 * leak out of the [load] entry point.
 */
data class SiloConfig(
    val port: Int,
    val host: String,
    val storageRoot: Path,
    val maxEntryBytes: Long,
    val allowUnsupportedFs: Boolean,
) {
    companion object {
        /** Reads `silo.*` keys from [config], falling back to documented defaults. */
        fun load(config: Config): SiloConfig =
            SiloConfig(
                port = config.optInt("silo.server.port", 8080),
                host = config.optString("silo.server.host", "0.0.0.0"),
                storageRoot =
                    Paths.get(
                        config.optString("silo.storage.root", "/data"),
                    ).toAbsolutePath().normalize(),
                maxEntryBytes =
                    config.optLong(
                        "silo.storage.max-entry-bytes",
                        2L * 1024 * 1024 * 1024,
                    ),
                allowUnsupportedFs =
                    config.optBoolean(
                        "silo.storage.allow-unsupported-fs",
                        false,
                    ),
            )

        private fun Config.optInt(
            path: String,
            default: Int,
        ): Int = if (hasPath(path)) getInt(path) else default

        private fun Config.optLong(
            path: String,
            default: Long,
        ): Long = if (hasPath(path)) getLong(path) else default

        private fun Config.optString(
            path: String,
            default: String,
        ): String = if (hasPath(path)) getString(path) else default

        private fun Config.optBoolean(
            path: String,
            default: Boolean,
        ): Boolean = if (hasPath(path)) getBoolean(path) else default
    }
}
