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

import at.favre.lib.crypto.bcrypt.BCrypt
import com.chrisjenx.silo.metadata.sqlite.SqliteMetadataIndex
import com.chrisjenx.silo.protocol.CacheKey
import com.chrisjenx.silo.storage.fs.ReconciliationEngine
import com.chrisjenx.silo.storage.fs.ShardLayout
import com.chrisjenx.silo.testing.TestKeys
import com.chrisjenx.silo.testing.TmpCacheRoot
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.Base64
import kotlin.io.path.exists

private const val WRITER = "w"
private const val WRITER_PW = "letmein"
private val AUTH = "Basic " + Base64.getEncoder().encodeToString("$WRITER:$WRITER_PW".toByteArray())

/**
 * Real kill -9 durability harness (#59). Boots the server in a child JVM,
 * streams a partial PUT, SIGKILLs the process mid-write, restarts a fresh
 * server over the same storage root, and asserts:
 *   1. the half-written blob never reached its final path (not persisted),
 *   2. a client GET sees a 404 (no half-entry is ever visible),
 *   3. startup reconciliation (here: a stale-tmp=0 pass) leaves no orphan
 *      `tmp.*` files behind.
 *
 * Tagged integrationTest because it spawns child JVMs.
 */
class Kill9MidPutChaosSpec : BehaviorSpec({

    given("a server killed with SIGKILL mid-PUT") {
        `when`("a fresh server restarts over the same storage root") {
            then("no half-written entry is visible and no orphan tmp survives recovery") {
                TmpCacheRoot.create("silo-kill9-").use { root ->
                    val usersFile = writeUsersFile(root)
                    val key = CacheKey.requireValid(TestKeys.valid(seed = 7))
                    val port = freePort()

                    withContext(Dispatchers.IO) {
                        // --- boot A, stream a partial PUT, then SIGKILL it ---
                        val procA = bootServer(root, usersFile, port)
                        var socket: Socket? = null
                        try {
                            awaitHealthy(port)
                            socket = openPartialPut(port, key.value)
                            delay(800) // let the server start streaming into tmp.*
                        } finally {
                            procA.destroyForcibly()
                            procA.waitFor()
                            socket?.close()
                        }

                        // Half-written blob must never have reached its final path.
                        ShardLayout.finalPath(root, key).exists() shouldBe false

                        // --- restart a fresh server B; the half-entry must be invisible ---
                        val portB = freePort()
                        val procB = bootServer(root, usersFile, portB)
                        try {
                            awaitHealthy(portB)
                            httpGetStatus(portB, "/cache/${key.value}") shouldBe 404
                        } finally {
                            procB.destroy()
                            procB.waitFor()
                        }

                        // --- recovery: a reconcile pass removes any crashed tmp ---
                        val index = SqliteMetadataIndex.open(root.resolve("silo.db"))
                        try {
                            ReconciliationEngine(
                                root = root,
                                index = index,
                                staleTmpAgeMs = 0L,
                                clock = Clock.systemUTC(),
                            ).reconcile()
                            index.get(key) shouldBe null
                            tmpFilesUnder(root) shouldBe emptyList()
                        } finally {
                            index.close()
                        }
                    }
                }
            }
        }
    }
})

private fun writeUsersFile(root: Path): Path {
    val hash = BCrypt.withDefaults().hashToString(4, WRITER_PW.toCharArray())
    val file = root.resolve("users.conf")
    Files.writeString(
        file,
        """silo { users = [ { username = "$WRITER", password-hash = "$hash", roles = ["read","write"] } ] }""",
    )
    return file
}

private fun freePort(): Int = ServerSocket(0).use { it.localPort }

private fun bootServer(
    root: Path,
    usersFile: Path,
    port: Int,
): Process {
    val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
    val classpath = System.getProperty("java.class.path")
    return ProcessBuilder(
        java,
        "-cp",
        classpath,
        "-Dktor.deployment.port=$port",
        "-Dsilo.storage.root=$root",
        "-Dsilo.auth.users-file=$usersFile",
        "-Dsilo.auth.anonymous-read=true",
        "io.ktor.server.netty.EngineMain",
    ).redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.appendTo(root.resolve("server-$port.log").toFile()))
        .start()
}

private suspend fun awaitHealthy(port: Int) {
    val client = HttpClient.newHttpClient()
    repeat(120) {
        val ok =
            runCatching {
                client.send(
                    HttpRequest.newBuilder(URI("http://127.0.0.1:$port/health")).GET().build(),
                    HttpResponse.BodyHandlers.discarding(),
                ).statusCode() == 200
            }.getOrDefault(false)
        if (ok) return
        delay(250)
    }
    error("server on port $port did not become healthy")
}

/**
 * Opens a connection, sends PUT headers declaring a 10 MB body but only 64 KB
 * of it, and returns the still-open socket. The caller SIGKILLs the server
 * while it is mid-stream, then closes this socket.
 */
private fun openPartialPut(
    port: Int,
    key: String,
): Socket {
    val socket = Socket("127.0.0.1", port)
    val out = socket.getOutputStream()
    val headers =
        "PUT /cache/$key HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Authorization: $AUTH\r\n" +
            "Content-Length: 10485760\r\n" +
            "Connection: close\r\n\r\n"
    out.write(headers.toByteArray())
    out.write(ByteArray(65536))
    out.flush()
    return socket
}

private fun httpGetStatus(
    port: Int,
    path: String,
): Int =
    HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
            .timeout(Duration.ofSeconds(5)).GET().build(),
        HttpResponse.BodyHandlers.discarding(),
    ).statusCode()

private fun tmpFilesUnder(root: Path): List<Path> {
    val cas = root.resolve("cas")
    if (!cas.exists()) return emptyList()
    Files.walk(cas).use { stream ->
        return stream.filter { Files.isRegularFile(it) && it.fileName.toString().startsWith("tmp.") }.toList()
    }
}
