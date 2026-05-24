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

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.BadJOSEException
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import java.net.URI
import java.text.ParseException

/**
 * Configuration for OAuth2 resource-server (OIDC / Bearer-token) mode.
 *
 * Coexists with HTTP Basic: a request may authenticate with either scheme.
 * Tokens are validated against the issuer's JWKS (signature + `iss` + `exp`,
 * and `aud` when [audience] is set), then the [rolesClaim] is mapped to
 * Silo's coarse [Role]s via [readRoleValues] / [writeRoleValues].
 */
data class OidcSettings(
    val issuer: String,
    val jwksUrl: String,
    val audience: String? = null,
    val rolesClaim: String = "roles",
    val readRoleValues: Set<String> = emptySet(),
    val writeRoleValues: Set<String> = emptySet(),
)

/** A successfully verified bearer token reduced to what Silo's routes need. */
data class VerifiedToken(
    val subject: String,
    val roles: Set<Role>,
)

/** Validates a bearer token string, returning `null` for any invalid token. */
fun interface TokenVerifier {
    fun verify(token: String): VerifiedToken?
}

/**
 * Maps the raw claim [values] (e.g. `["cache:read", "cache:write"]` or the
 * space-delimited `scope` string already split) to Silo roles. WRITE implies
 * READ so a writer can still serve GETs. Pure — unit-tested directly.
 */
fun mapRoles(
    values: Collection<String>,
    settings: OidcSettings,
): Set<Role> {
    val roles = mutableSetOf<Role>()
    if (values.any { it in settings.readRoleValues }) roles += Role.READ
    if (values.any { it in settings.writeRoleValues }) roles += Role.WRITE
    if (Role.WRITE in roles) roles += Role.READ
    return roles
}

/** Extracts role values from [claims], tolerating both array and space/comma string forms. */
fun roleValues(
    claims: JWTClaimsSet,
    rolesClaim: String,
): List<String> =
    when (val raw = claims.getClaim(rolesClaim)) {
        is String -> raw.split(' ', ',').map { it.trim() }.filter { it.isNotEmpty() }
        is Collection<*> -> raw.mapNotNull { it?.toString() }
        else -> emptyList()
    }

/**
 * [TokenVerifier] backed by Nimbus JOSE. The [jwkSource] supplies signing keys
 * — in production a remote, cached, auto-rotating JWKS endpoint (see
 * [fromJwksUrl]); in tests an in-memory key set. RS256 only.
 */
class NimbusTokenVerifier(
    private val settings: OidcSettings,
    jwkSource: JWKSource<SecurityContext>,
) : TokenVerifier {
    private val processor =
        DefaultJWTProcessor<SecurityContext>().apply {
            jwsKeySelector = JWSVerificationKeySelector(JWSAlgorithm.RS256, jwkSource)
            jwtClaimsSetVerifier = claimsVerifier()
        }

    private fun claimsVerifier(): DefaultJWTClaimsVerifier<SecurityContext> {
        val exactMatch = JWTClaimsSet.Builder().issuer(settings.issuer).build()
        val required = setOf("exp", "sub")
        return if (settings.audience != null) {
            DefaultJWTClaimsVerifier(settings.audience, exactMatch, required)
        } else {
            DefaultJWTClaimsVerifier(exactMatch, required)
        }
    }

    override fun verify(token: String): VerifiedToken? {
        val claims = process(token) ?: return null
        val subject = claims.subject ?: return null
        return VerifiedToken(subject, mapRoles(roleValues(claims, settings.rolesClaim), settings))
    }

    /** Signature + claim-set validation; any failure collapses to null (treated as 401). */
    private fun process(token: String): JWTClaimsSet? =
        try {
            processor.process(token, null)
        } catch (_: BadJOSEException) {
            null
        } catch (_: JOSEException) {
            null
        } catch (_: ParseException) {
            null
        }

    companion object {
        /**
         * Builds a verifier whose keys come from a remote JWKS endpoint with
         * caching + retry, so issuer key rotation is picked up automatically.
         */
        fun fromJwksUrl(settings: OidcSettings): NimbusTokenVerifier {
            val source =
                JWKSourceBuilder.create<SecurityContext>(URI(settings.jwksUrl).toURL())
                    .retrying(true)
                    .build()
            return NimbusTokenVerifier(settings, source)
        }
    }
}
