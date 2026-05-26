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

import com.chrisjenx.silo.server.auth.PasswordHasher

/**
 * `java -jar silo.jar hash-password` — read a password without echoing it and
 * print the bcrypt `$2a$12$...` hash to paste into `users.conf`.
 *
 * On an interactive console the password is entered twice (no echo) to catch
 * typos. When stdin is piped (CI/scripts) a single line is read instead so
 * `echo secret | java -jar silo.jar hash-password` works headless. Returns the
 * process exit code: `0` on success, `1` on a mismatch or empty input.
 */
internal fun runHashPassword(): Int {
    val console = System.console()
    val password: CharArray =
        if (console != null) {
            val first = console.readPassword("Password: ")
            val second = console.readPassword("Confirm:  ")
            if (first == null || second == null || !first.contentEquals(second)) {
                System.err.println("Passwords did not match.")
                return 1
            }
            second.fill(' ')
            first
        } else {
            val line = readlnOrNull()
            if (line.isNullOrEmpty()) {
                System.err.println("No password supplied on stdin.")
                return 1
            }
            line.toCharArray()
        }
    val hash = PasswordHasher.hash(password)
    password.fill(' ') // wipe the plaintext from memory once hashed
    println(hash)
    return 0
}
