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
package com.chrisjenx.silo.server.auth

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import java.nio.file.Files
import java.nio.file.Path

/** A single principal with the roles they may exercise. */
data class User(
    val username: String,
    val passwordHash: String,
    val roles: Set<Role>,
)

/** Coarse-grained permission attached to a [User]. */
enum class Role { READ, WRITE }

/**
 * In-memory snapshot of the users known to Silo.
 *
 * Construction is deliberately pure: tests new it up from an in-memory map
 * and production reads from a HOCON file via [loadFromFile]. Hot reload is
 * deferred to a follow-up issue.
 */
class UserStore(initial: Iterable<User>) {
    private val byName: Map<String, User> = initial.associateBy { it.username }

    /** Number of principals currently held. */
    val size: Int get() = byName.size

    /** Look up by username, or `null` if no such user. */
    fun findByName(username: String): User? = byName[username]

    companion object {
        /**
         * Load users from a HOCON file. Schema:
         *
         * ```
         * silo {
         *   users = [
         *     { username = "alice", password-hash = "$2a$...", roles = ["read", "write"] }
         *   ]
         * }
         * ```
         *
         * Missing file or empty array both yield an empty store. The caller
         * is responsible for refusing to boot if anonymous-read is also off.
         */
        fun loadFromFile(path: Path): UserStore {
            if (!Files.exists(path)) return UserStore(emptyList())
            val config = ConfigFactory.parseFile(path.toFile()).resolve()
            return loadFromConfig(config)
        }

        fun loadFromConfig(config: Config): UserStore {
            val users =
                if (config.hasPath("silo.users")) {
                    config.getConfigList("silo.users").map { entry ->
                        User(
                            username = entry.getString("username"),
                            passwordHash = entry.getString("password-hash"),
                            roles =
                                entry.getStringList("roles")
                                    .map { Role.valueOf(it.uppercase()) }
                                    .toSet(),
                        )
                    }
                } else {
                    emptyList()
                }
            return UserStore(users)
        }
    }
}
