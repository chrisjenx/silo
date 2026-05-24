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

import com.nimbusds.jwt.JWTClaimsSet
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

private val SETTINGS =
    OidcSettings(
        issuer = "https://issuer.test",
        jwksUrl = "https://issuer.test/jwks",
        rolesClaim = "roles",
        readRoleValues = setOf("cache:read"),
        writeRoleValues = setOf("cache:write"),
    )

/** Pure claims→roles mapping (#54). No JWT crypto here — that's OidcAuthSpec. */
class OidcRoleMappingSpec : StringSpec({

    "read value grants READ only" {
        mapRoles(listOf("cache:read"), SETTINGS) shouldBe setOf(Role.READ)
    }

    "write value grants WRITE and implies READ" {
        mapRoles(listOf("cache:write"), SETTINGS) shouldBe setOf(Role.READ, Role.WRITE)
    }

    "unknown values grant nothing" {
        mapRoles(listOf("admin", "other"), SETTINGS) shouldBe emptySet()
    }

    "empty values grant nothing" {
        mapRoles(emptyList(), SETTINGS) shouldBe emptySet()
    }

    "roleValues reads a JSON array claim" {
        val claims = JWTClaimsSet.Builder().claim("roles", listOf("cache:read", "cache:write")).build()
        roleValues(claims, "roles") shouldBe listOf("cache:read", "cache:write")
    }

    "roleValues splits a space-delimited scope string" {
        val claims = JWTClaimsSet.Builder().claim("scope", "cache:read cache:write").build()
        roleValues(claims, "scope") shouldBe listOf("cache:read", "cache:write")
    }

    "roleValues splits a comma-delimited string and trims" {
        val claims = JWTClaimsSet.Builder().claim("roles", "cache:read, cache:write").build()
        roleValues(claims, "roles") shouldBe listOf("cache:read", "cache:write")
    }

    "roleValues returns empty when claim absent" {
        roleValues(JWTClaimsSet.Builder().build(), "roles") shouldBe emptyList()
    }
})
