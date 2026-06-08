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

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration

class GitHubReleaseClient(
    private val repo: String,
    private val token: String?,
    private val apiBase: String = "https://api.github.com",
    private val userAgent: String = "silo-updater",
    private val http: HttpClient =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build(),
) : ReleaseClient {
    override fun latest(includePrerelease: Boolean): Release =
        if (includePrerelease) {
            Release.listFromJson(getString("$apiBase/repos/$repo/releases?per_page=20"))
                .maxByOrNull { it.version }
                ?: error("No releases found for $repo")
        } else {
            Release.fromJson(getString("$apiBase/repos/$repo/releases/latest"))
        }

    override fun byTag(tag: String): Release = Release.fromJson(getString("$apiBase/repos/$repo/releases/tags/$tag"))

    override fun fetchText(url: String): String = getString(url)

    override fun download(
        url: String,
        dest: Path,
    ) {
        val res = http.send(baseRequest(url).GET().build(), HttpResponse.BodyHandlers.ofFile(dest))
        require(res.statusCode() in 200..299) { "Download failed (${res.statusCode()}) for $url" }
    }

    override fun attestationBundle(sha256Hex: String): String = getString("$apiBase/repos/$repo/attestations/sha256:$sha256Hex")

    private fun getString(url: String): String {
        val res = http.send(baseRequest(url).GET().build(), HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() == 403 && res.headers().firstValue("x-ratelimit-remaining").orElse("") == "0") {
            error("GitHub API rate limit exceeded. Set SILO_UPDATE_TOKEN to raise the limit.")
        }
        require(res.statusCode() in 200..299) { "GitHub API ${res.statusCode()} for $url: ${res.body().take(200)}" }
        return res.body()
    }

    private fun baseRequest(url: String): HttpRequest.Builder {
        val b =
            HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", userAgent)
                .header("Accept", "application/vnd.github+json")
                .timeout(Duration.ofMinutes(5))
        if (!token.isNullOrBlank()) b.header("Authorization", "Bearer $token")
        return b
    }
}
