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

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

/** Verbs supplied by an optional plugin module that may be absent from a given build. */
private val RESERVED_PLUGIN_VERBS = setOf("update")

/**
 * Handles CLI subcommands. Returns the process exit code to use, or null to mean
 * "no subcommand matched — start the server". [extra] are build-specific plugin
 * subcommands (e.g. `update` in the full jar; empty in the slim Docker jar).
 */
fun runCli(
    args: Array<String>,
    extra: List<Subcommand>,
): Int? {
    if (args.any { it == "--version" || it == "-V" }) {
        println(SiloVersion.line())
        return 0
    }
    if (args.firstOrNull() == "hash-password") return runHashPassword()
    return dispatchVerb(args, extra)
}

/** Routes a verb to a plugin or returns a reserved-verb error code (or null = start server). */
private fun dispatchVerb(
    args: Array<String>,
    extra: List<Subcommand>,
): Int? {
    val verb = args.firstOrNull()
    val plugin = extra.firstOrNull { it.name == verb }
    if (plugin != null) return plugin.run(args.drop(1))
    if (verb != null && verb in RESERVED_PLUGIN_VERBS) {
        System.err.println(notBundledMessage(verb))
        return 1
    }
    return null
}

private fun notBundledMessage(verb: String): String =
    "'$verb' isn't bundled in this build (e.g. the Docker image). " +
        "Update by pulling a new image — docker pull ghcr.io/chrisjenx/silo:<version> — see docs/operations.md."

/** Starts the Ktor server (blocks). Shared by the slim and full entry points. */
fun startServer() {
    val config = SiloConfig.load(com.typesafe.config.ConfigFactory.load())
    embeddedServer(
        factory = Netty,
        port = config.port,
        host = config.host,
        module = { module() },
    ).start(wait = true)
}
