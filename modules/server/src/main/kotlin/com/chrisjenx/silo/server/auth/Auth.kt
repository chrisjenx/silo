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

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.basic

/** Principal stored on the call when Basic auth succeeds. */
data class SiloPrincipal(
    val username: String,
    val roles: Set<Role>,
)

/**
 * Top-level auth state for the server. [anonymousRead] = legacy node parity
 * (GET/HEAD without credentials); PUT always requires WRITE regardless.
 */
data class AuthSettings(
    val anonymousRead: Boolean,
    val users: UserStore,
    val verifier: PasswordVerifier,
)

/**
 * Installs the `silo` Basic auth realm. The realm is configured as
 * `optional = true` so route handlers can decide per-verb whether the
 * absence of credentials is acceptable.
 */
fun Application.installSiloAuth(settings: AuthSettings) {
    install(Authentication) {
        basic("silo") {
            realm = "silo"
            validate { creds ->
                val user = settings.users.findByName(creds.name) ?: return@validate null
                val ok = settings.verifier.verify(user.username, creds.password.toCharArray(), user.passwordHash)
                if (ok) SiloPrincipal(user.username, user.roles) else null
            }
        }
    }
}
