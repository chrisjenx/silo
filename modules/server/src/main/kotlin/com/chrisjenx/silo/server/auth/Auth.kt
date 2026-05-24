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
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.basic
import io.ktor.server.auth.bearer
import io.ktor.server.routing.Route

/** Principal stored on the call when Basic auth succeeds. */
data class SiloPrincipal(
    val username: String,
    val roles: Set<Role>,
)

/** Ktor auth provider name for the HTTP Basic realm. */
const val BASIC_PROVIDER = "silo"

/** Ktor auth provider name for the OIDC / Bearer realm. */
const val OIDC_PROVIDER = "silo-oidc"

/**
 * Top-level auth state for the server. [anonymousRead] = legacy node parity
 * (GET/HEAD without credentials); PUT always requires WRITE regardless.
 *
 * [tokenVerifier] enables OAuth2 resource-server (Bearer) mode alongside
 * Basic; when null, only Basic is offered.
 */
data class AuthSettings(
    val anonymousRead: Boolean,
    val users: UserStore,
    val verifier: PasswordVerifier,
    val tokenVerifier: TokenVerifier? = null,
) {
    /** Auth provider names to wire onto routes — Bearer is added only when enabled. */
    val providerNames: List<String>
        get() = if (tokenVerifier != null) listOf(BASIC_PROVIDER, OIDC_PROVIDER) else listOf(BASIC_PROVIDER)
}

/**
 * Wraps [build] in an `authenticate` block spanning every enabled Silo auth
 * provider (Basic, and Bearer when configured). Centralises the vararg spread
 * so call sites stay clean.
 */
@Suppress("SpreadOperator")
fun Route.authenticateSilo(
    settings: AuthSettings,
    optional: Boolean = false,
    build: Route.() -> Unit,
) {
    authenticate(*settings.providerNames.toTypedArray(), optional = optional, build = build)
}

/**
 * Installs the Silo auth realms. Both are `optional = true` so route handlers
 * decide per-verb whether the absence of credentials is acceptable. Basic is
 * always installed; the Bearer realm is added only when [AuthSettings.tokenVerifier]
 * is configured.
 */
fun Application.installSiloAuth(settings: AuthSettings) {
    install(Authentication) {
        basic(BASIC_PROVIDER) {
            realm = "silo"
            validate { creds ->
                val user = settings.users.findByName(creds.name) ?: return@validate null
                val ok = settings.verifier.verify(user.username, creds.password.toCharArray(), user.passwordHash)
                if (ok) SiloPrincipal(user.username, user.roles) else null
            }
        }
        settings.tokenVerifier?.let { verifier ->
            bearer(OIDC_PROVIDER) {
                realm = "silo"
                authenticate { credential ->
                    verifier.verify(credential.token)?.let { SiloPrincipal(it.subject, it.roles) }
                }
            }
        }
    }
}
