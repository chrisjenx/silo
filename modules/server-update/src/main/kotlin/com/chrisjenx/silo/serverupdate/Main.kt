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

package com.chrisjenx.silo.serverupdate

import com.chrisjenx.silo.server.runCli
import com.chrisjenx.silo.server.startServer
import kotlin.system.exitProcess

/**
 * Full entry point (standalone download, with self-update). Run with
 * `java -jar silo-<v>-all.jar`. Wires the `update` plugin explicitly, then delegates to the
 * shared [runCli]/[startServer] in :server.
 */
fun main(args: Array<String>) {
    runCli(args, extra = listOf(UpdateSubcommand))?.let { exitProcess(it) }
    startServer()
}
