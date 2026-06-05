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
import java.util.UUID

class Updater(
    private val releaseClient: ReleaseClient,
    private val attestationVerifier: AttestationVerifier,
    private val replacer: AtomicJarReplacer,
    private val repo: String,
) {
    fun run(
        request: UpdateRequest,
        jarOverride: Path? = null,
    ): UpdateOutcome {
        val jar = locateJar(request.currentVersion, jarOverride) ?: return locateFailure(request.currentVersion, jarOverride)
        if (request.rollback) return doRollback(jar)
        val ready = resolveTarget(request) ?: return resolveTargetFailure(request)
        return if (ready.shortCircuit != null) ready.shortCircuit else downloadVerifySwap(jar, ready, request.verifyAttestation)
    }

    private fun locateJar(
        currentVersion: String,
        jarOverride: Path?,
    ): Path? =
        when (val located = jarOverride ?: JarLocator.locate(currentVersion)) {
            is Path -> located
            is JarLocated -> located.path
            else -> null
        }

    private fun locateFailure(
        currentVersion: String,
        jarOverride: Path?,
    ): UpdateOutcome.Failed {
        val located = jarOverride ?: JarLocator.locate(currentVersion)
        return (located as? UpdateOutcome.Failed) ?: UpdateOutcome.Failed("Could not resolve the jar to update.")
    }

    private fun doRollback(jar: Path): UpdateOutcome =
        if (replacer.rollback(jar)) {
            UpdateOutcome.RolledBack(jar)
        } else {
            UpdateOutcome.Failed("No backup (${jar.fileName}.bak) found to roll back to.")
        }

    /**
     * Resolves the current version and target release, computing any short-circuit outcome
     * (up-to-date, check-only). Returns null on parse or network failure (see [resolveTargetFailure]).
     */
    private fun resolveTarget(request: UpdateRequest): ReadyToInstall? {
        val current = runCatching { SemVer.parse(request.currentVersion) }.getOrNull() ?: return null
        val target =
            runCatching {
                if (request.toTag != null) releaseClient.byTag(request.toTag) else releaseClient.latest(request.includePrerelease)
            }.getOrNull() ?: return null
        // A pinned --to always proceeds (allows re-install/downgrade); otherwise short-circuit if not newer.
        val shortCircuit =
            when {
                request.toTag == null && target.version <= current -> UpdateOutcome.UpToDate(current)
                request.checkOnly -> UpdateOutcome.UpdateAvailable(current, target.version)
                else -> null
            }
        return ReadyToInstall(current, target, shortCircuit)
    }

    /** Produces a descriptive failure when [resolveTarget] returned null. */
    private fun resolveTargetFailure(request: UpdateRequest): UpdateOutcome.Failed {
        runCatching { SemVer.parse(request.currentVersion) }.getOrNull()
            ?: return UpdateOutcome.Failed("Current version '${request.currentVersion}' is not semver.")
        return UpdateOutcome.Failed("Could not fetch release info.")
    }

    // The broad catch guards the download/verify/swap sequence: any failure must surface as
    // UpdateOutcome.Failed (and the temp file cleaned up) rather than crash the command.
    @Suppress("TooGenericExceptionCaught")
    private fun downloadVerifySwap(
        jar: Path,
        ready: ReadyToInstall,
        verifyAttestation: Boolean,
    ): UpdateOutcome {
        val assets = resolveAssets(ready.target) ?: return UpdateOutcome.Failed("Release ${ready.target.tag} is missing required assets.")
        val tmp = jar.resolveSibling(".silo-update-${UUID.randomUUID()}.tmp")
        try {
            releaseClient.download(assets.jarUrl, tmp)
            val preCheckFail = preReplaceChecks(tmp, assets, ready.target.tag, verifyAttestation)
            if (preCheckFail != null) return preCheckFail
            replacer.replace(jar = jar, verifiedSource = tmp)
            return UpdateOutcome.Updated(from = ready.current, to = ready.target.version, installedAt = jar)
        } catch (e: Exception) {
            return UpdateOutcome.Failed("Update failed: ${e.message}")
        } finally {
            runCatching { Files.deleteIfExists(tmp) }
        }
    }

    /** Runs checksum and (optionally) attestation checks on the downloaded tmp file. */
    private fun preReplaceChecks(
        tmp: Path,
        assets: ReleaseAssets,
        tag: String,
        verifyAttestation: Boolean,
    ): UpdateOutcome.Failed? {
        val checksums = releaseClient.fetchText(assets.checksumsUrl)
        val checksumFail = checksumOrFail(tmp, tag, checksums)
        return checksumFail ?: if (verifyAttestation) verifyAttestation(tmp, tag) else null
    }

    private fun resolveAssets(target: Release): ReleaseAssets? {
        val jarUrl = target.asset("silo.jar")?.downloadUrl ?: return null
        val checksumsUrl = target.asset("checksums.txt")?.downloadUrl ?: return null
        return ReleaseAssets(jarUrl, checksumsUrl)
    }

    private fun checksumOrFail(
        tmp: Path,
        tag: String,
        checksumsTxt: String,
    ): UpdateOutcome.Failed? =
        if (ChecksumVerifier.matches(tmp, "silo.jar", checksumsTxt)) {
            null
        } else {
            UpdateOutcome.Failed("SHA-256 mismatch for $tag — refusing to install.")
        }

    private fun verifyAttestation(
        tmp: Path,
        tag: String,
    ): UpdateOutcome.Failed? {
        val digest = ChecksumVerifier.sha256(tmp)
        val bundle =
            runCatching { releaseClient.attestationBundle(digest) }
                .getOrElse { return UpdateOutcome.Failed("Could not fetch attestation: ${it.message}") }
        return runCatching { attestationVerifier.verify(digest, bundle, repo, tag) }
            .exceptionOrNull()
            ?.let { UpdateOutcome.Failed("Provenance check failed: ${it.message}") }
    }

    private data class ReleaseAssets(val jarUrl: String, val checksumsUrl: String)

    private data class ReadyToInstall(val current: SemVer, val target: Release, val shortCircuit: UpdateOutcome?)
}
