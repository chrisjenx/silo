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
    val auditDir: Path? = null,
    val sqliteCheckpointIntervalSeconds: Long = 300,
    val sqliteVacuumIntervalSeconds: Long = 86_400,
    // Capacity + eviction caps (documented in docs/limits.md). Surfaced via
    // /api/config and the admin dashboard; enforcement wiring tracked separately.
    val maxBytes: Long = 100L * 1024 * 1024 * 1024,
    val maxEntries: Long = 1_000_000,
    val reservedFreeBytes: Long = 5L * 1024 * 1024 * 1024,
    val reservedFreeInodes: Long = 100_000,
    val maxAgeDays: Int = 30,
    val maxDeletesPerCycle: Int = 1_000,
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
                sqliteCheckpointIntervalSeconds =
                    config.optLong("silo.sqlite.checkpoint-interval-seconds", 300L),
                sqliteVacuumIntervalSeconds =
                    config.optLong("silo.sqlite.vacuum-interval-seconds", 86_400L),
                auditDir =
                    if (config.optBoolean("silo.audit.enabled", false)) {
                        Paths.get(config.optString("silo.audit.dir", "/data/audit"))
                    } else {
                        null
                    },
                maxBytes = config.optLong("silo.storage.max-bytes", 100L * 1024 * 1024 * 1024),
                maxEntries = config.optLong("silo.storage.max-entries", 1_000_000L),
                reservedFreeBytes = config.optLong("silo.storage.reserved-free-bytes", 5L * 1024 * 1024 * 1024),
                reservedFreeInodes = config.optLong("silo.storage.reserved-free-inodes", 100_000L),
                maxAgeDays = config.optInt("silo.eviction.max-age-days", 30),
                maxDeletesPerCycle = config.optInt("silo.eviction.max-deletes-per-cycle", 1_000),
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
