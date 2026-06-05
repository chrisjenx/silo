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

data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: String? = null,
) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int {
        major.compareTo(other.major).let { if (it != 0) return it }
        minor.compareTo(other.minor).let { if (it != 0) return it }
        patch.compareTo(other.patch).let { if (it != 0) return it }
        // Release (null pre-release) outranks any prerelease of the same x.y.z.
        return when {
            preRelease == null && other.preRelease == null -> 0
            preRelease == null -> 1
            other.preRelease == null -> -1
            else -> preRelease.compareTo(other.preRelease)
        }
    }

    companion object {
        private val PATTERN = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-(.+))?$""")

        fun parse(raw: String): SemVer {
            val m =
                PATTERN.matchEntire(raw.trim())
                    ?: throw IllegalArgumentException("Not a semantic version: '$raw'")
            // groupValues[0] is the whole match; 1..3 are major/minor/patch, 4 is the optional pre-release.
            val groups = m.groupValues
            return SemVer(groups[1].toInt(), groups[2].toInt(), groups[3].toInt(), groups[4].ifEmpty { null })
        }
    }
}
