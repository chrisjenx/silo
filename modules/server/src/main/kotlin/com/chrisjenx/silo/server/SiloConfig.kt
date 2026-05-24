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

import com.chrisjenx.silo.server.auth.OidcSettings
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
    val anonymousRead: Boolean = true,
    val usersConfPath: Path? = null,
    val verifySha256OnRead: Boolean = false,
    val oidc: OidcSettings? = null,
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
                anonymousRead =
                    config.optBoolean(
                        "silo.auth.anonymous-read",
                        true,
                    ),
                usersConfPath =
                    if (config.hasPath("silo.auth.users-file")) {
                        Paths.get(config.getString("silo.auth.users-file"))
                    } else {
                        null
                    },
                verifySha256OnRead =
                    config.optBoolean(
                        "silo.storage.verify-sha256-on-read",
                        false,
                    ),
                oidc = loadOidc(config),
            )

        /** Parses `silo.auth.oidc.*`; returns null unless `enabled = true`. */
        private fun loadOidc(config: Config): OidcSettings? {
            if (!config.optBoolean("silo.auth.oidc.enabled", false)) return null
            return OidcSettings(
                issuer = config.getString("silo.auth.oidc.issuer"),
                jwksUrl = config.getString("silo.auth.oidc.jwks-url"),
                audience =
                    if (config.hasPath("silo.auth.oidc.audience")) {
                        config.getString("silo.auth.oidc.audience")
                    } else {
                        null
                    },
                rolesClaim = config.optString("silo.auth.oidc.roles-claim", "roles"),
                readRoleValues = config.optStringSet("silo.auth.oidc.read-roles"),
                writeRoleValues = config.optStringSet("silo.auth.oidc.write-roles"),
            )
        }

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

        private fun Config.optStringSet(path: String): Set<String> = if (hasPath(path)) getStringList(path).toSet() else emptySet()
    }
}
