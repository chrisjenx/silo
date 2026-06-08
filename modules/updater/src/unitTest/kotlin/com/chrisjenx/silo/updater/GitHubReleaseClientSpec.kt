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
package com.chrisjenx.silo.updater

import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.StringSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.net.InetSocketAddress
import java.nio.file.Files

class GitHubReleaseClientSpec : StringSpec({

    lateinit var server: HttpServer
    var sawUserAgent = ""

    beforeSpec {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/repos/acme/silo/releases/latest") { ex ->
            sawUserAgent = ex.requestHeaders.getFirst("User-Agent") ?: ""
            val body =
                """
                {"tag_name":"v0.2.0","prerelease":false,"assets":[
                {"name":"silo.jar","browser_download_url":"http://x/silo.jar"}]}
                """.trimIndent()
            ex.sendResponseHeaders(200, body.toByteArray().size.toLong())
            ex.responseBody.use { it.write(body.toByteArray()) }
        }
        server.createContext("/blob.txt") { ex ->
            val body = "deadbeef  silo.jar\n"
            ex.sendResponseHeaders(200, body.toByteArray().size.toLong())
            ex.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
    }
    afterSpec { server.stop(0) }

    fun baseUrl() = "http://127.0.0.1:${server.address.port}"

    "latest() requests the right path, sends a User-Agent, and parses the release" {
        val client = GitHubReleaseClient(repo = "acme/silo", token = null, apiBase = baseUrl())
        val release = client.latest(includePrerelease = false)
        release.version shouldBe SemVer(0, 2, 0)
        sawUserAgent shouldContain "silo"
    }

    "fetchText returns the full body" {
        val client = GitHubReleaseClient(repo = "acme/silo", token = null, apiBase = baseUrl())
        client.fetchText("${baseUrl()}/blob.txt") shouldContain "silo.jar"
    }

    "download streams bytes to disk" {
        val client = GitHubReleaseClient(repo = "acme/silo", token = null, apiBase = baseUrl())
        val dest = tempdir().toPath().resolve("out.txt")
        client.download("${baseUrl()}/blob.txt", dest)
        Files.readString(dest) shouldContain "silo.jar"
    }
})
