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
@file:JvmName("Main")

package com.chrisjenx.silo.server

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlin.system.exitProcess

/**
 * Ktor entry point. Run with `./gradlew :server:run` or `java -jar silo.jar`.
 * Reads `application.conf` from the classpath, then starts Netty on the
 * configured `silo.server.{host,port}`.
 *
 * `--version` / `-V` prints `silo <version> (<sha>) jvm <javaVersion>` and exits.
 * `hash-password` reads a password (no echo) and prints its bcrypt hash.
 */
fun main(args: Array<String>) {
    if (args.any { it == "--version" || it == "-V" }) {
        println(SiloVersion.line())
        return
    }
    if (args.firstOrNull() == "hash-password") {
        exitProcess(runHashPassword())
    }
    val config = SiloConfig.load(com.typesafe.config.ConfigFactory.load())
    embeddedServer(
        factory = Netty,
        port = config.port,
        host = config.host,
        module = { module() },
    ).start(wait = true)
}
