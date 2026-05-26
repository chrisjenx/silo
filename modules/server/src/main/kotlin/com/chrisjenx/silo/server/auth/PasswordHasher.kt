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

import at.favre.lib.crypto.bcrypt.BCrypt

/**
 * Produces bcrypt hashes for the `users.conf` `password-hash` field.
 *
 * The `hash-password` CLI subcommand ([com.chrisjenx.silo.server.runHashPassword])
 * is the only caller. Output is `$2a$12$...` — the same version and cost the
 * docs and bundled examples use, so a generated hash drops straight into a
 * users file and [PasswordVerifier] accepts it.
 */
object PasswordHasher {
    /** bcrypt cost factor. 12 ≈ 250 ms/verify — see [PasswordVerifier]. */
    const val COST: Int = 12

    /** Hash [plaintext] into a self-describing `$2a$12$...` bcrypt string. */
    fun hash(plaintext: CharArray): String = BCrypt.withDefaults().hashToString(COST, plaintext)
}
