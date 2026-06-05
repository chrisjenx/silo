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

import java.nio.file.Files
import java.nio.file.Path

/** Successful jar resolution. (Failure is reported as [UpdateOutcome.Failed].) */
data class JarLocated(val path: Path)

object JarLocator {

    /**
     * Resolves the jar to replace. [codeSource] defaults to this class's own code source so
     * production callers pass nothing; tests inject a path. Returns [JarLocated] on success or
     * [UpdateOutcome.Failed] describing why self-update can't proceed.
     */
    fun locate(currentVersion: String, codeSource: Path? = ownCodeSource()): Any {
        if (currentVersion == "dev" || currentVersion.isBlank()) {
            return UpdateOutcome.Failed(
                "Running an unversioned/dev build (version='$currentVersion'); nothing to update against.",
            )
        }
        if (codeSource == null || !codeSource.toString().endsWith(".jar") || !Files.isRegularFile(codeSource)) {
            return UpdateOutcome.Failed(
                "Self-update only works when running a packaged jar (java -jar silo.jar), not an exploded classpath.",
            )
        }
        val parent = codeSource.toAbsolutePath().parent
        if (parent == null || !Files.isWritable(parent) || !Files.isWritable(codeSource)) {
            return UpdateOutcome.Failed(
                "The jar at $codeSource is not writable — this looks like a managed/container install " +
                    "(e.g. Docker /app/silo.jar). Update by pulling a new image or via your package manager.",
            )
        }
        return JarLocated(codeSource.toAbsolutePath())
    }

    private fun ownCodeSource(): Path? =
        runCatching {
            val uri = JarLocator::class.java.protectionDomain?.codeSource?.location?.toURI()
                ?: return null
            Path.of(uri)
        }.getOrNull()
}
