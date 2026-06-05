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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class ReleaseAsset(val name: String, val downloadUrl: String)

data class Release(
    val tag: String,
    val version: SemVer,
    val prerelease: Boolean,
    val assets: List<ReleaseAsset>,
) {
    fun asset(name: String): ReleaseAsset? = assets.firstOrNull { it.name == name }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(text: String): Release = json.decodeFromString<GhRelease>(text).toDomain()

        fun listFromJson(text: String): List<Release> = json.decodeFromString<List<GhRelease>>(text).mapNotNull { it.toDomainOrNull() }
    }
}

@Serializable
private data class GhRelease(
    @SerialName("tag_name") val tagName: String,
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    val assets: List<GhAsset> = emptyList(),
) {
    fun toDomain(): Release = Release(tagName, SemVer.parse(tagName), prerelease, assets.map { ReleaseAsset(it.name, it.url) })

    /** Tolerant variant for list endpoints: skip non-semver tags instead of throwing. */
    fun toDomainOrNull(): Release? = runCatching { toDomain() }.getOrNull()
}

@Serializable
private data class GhAsset(
    val name: String,
    @SerialName("browser_download_url") val url: String,
)
