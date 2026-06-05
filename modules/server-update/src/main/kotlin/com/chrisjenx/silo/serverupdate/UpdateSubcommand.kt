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
package com.chrisjenx.silo.serverupdate

import com.chrisjenx.silo.server.SiloVersion
import com.chrisjenx.silo.server.Subcommand
import com.chrisjenx.silo.updater.AtomicJarReplacer
import com.chrisjenx.silo.updater.GitHubReleaseClient
import com.chrisjenx.silo.updater.SigstoreAttestationVerifier
import com.chrisjenx.silo.updater.UpdateOutcome
import com.chrisjenx.silo.updater.UpdateRequest
import com.chrisjenx.silo.updater.Updater

object UpdateSubcommand : Subcommand {
    override val name: String = "update"
    private const val EXIT_OK = 0
    private const val EXIT_ERROR = 1
    private const val EXIT_UPDATE_AVAILABLE = 10

    private val USAGE =
        """
        Usage: silo update [options]
          (no options)              download + verify + install the latest release, then prompt to restart
          --check                   report whether a newer version exists (exit 10 if so); no changes
          --to <tag>                install a specific version, e.g. --to v0.2.0
          --yes                     skip the confirmation prompt
          --prerelease              consider prereleases
          --no-verify-attestation   skip provenance check (SHA-256 still enforced)
          --rollback                restore the previous jar from silo.jar.bak
          --repo <owner/name>       update source (default: chrisjenx/silo; or SILO_UPDATE_REPO)
        Env: SILO_UPDATE_TOKEN raises GitHub API rate limits / enables private forks.
        """.trimIndent()

    /** Real entry from Main: reads SiloVersion + env, prompts on a TTY, runs the real Updater. */
    override fun run(args: List<String>): Int {
        if (args.contains("--help") || args.contains("-h")) {
            println(USAGE)
            return EXIT_OK
        }
        return run(
            args = args,
            currentVersion = SiloVersion.version,
            repoDefault = "chrisjenx/silo",
            envRepo = System.getenv("SILO_UPDATE_REPO"),
            envToken = System.getenv("SILO_UPDATE_TOKEN"),
            confirm = ::promptYesNo,
        )
    }

    /** Testable core. [execute] defaults to the real Updater built from parsed options. */
    @Suppress("LongParameterList")
    fun run(
        args: List<String>,
        currentVersion: String,
        repoDefault: String,
        envRepo: String?,
        envToken: String?,
        confirm: (String) -> Boolean,
        execute: ((UpdateRequest) -> UpdateOutcome)? = null,
    ): Int {
        val repo = optionValue(args, "--repo") ?: envRepo ?: repoDefault
        val request =
            UpdateRequest(
                currentVersion = currentVersion,
                toTag = optionValue(args, "--to"),
                checkOnly = args.contains("--check"),
                includePrerelease = args.contains("--prerelease"),
                verifyAttestation = !args.contains("--no-verify-attestation"),
                rollback = args.contains("--rollback"),
            )

        if (!request.checkOnly && !request.rollback && !args.contains("--yes")) {
            if (!confirm("Update silo from $currentVersion using $repo? [y/N] ")) {
                println("Aborted.")
                return EXIT_OK
            }
        }

        val runner = execute ?: { req -> defaultUpdater(repo, envToken).run(req) }
        val outcome = runner(request)
        return report(outcome)
    }

    private fun defaultUpdater(
        repo: String,
        token: String?,
    ): Updater =
        Updater(
            releaseClient = GitHubReleaseClient(repo = repo, token = token),
            attestationVerifier = SigstoreAttestationVerifier(),
            replacer = AtomicJarReplacer(),
            repo = repo,
        )

    private fun report(outcome: UpdateOutcome): Int =
        when (outcome) {
            is UpdateOutcome.UpToDate -> {
                println("silo ${outcome.current} is up to date.")
                EXIT_OK
            }
            is UpdateOutcome.UpdateAvailable -> {
                println(
                    "A newer version is available: ${outcome.current} -> ${outcome.latest}. " +
                        "Run 'silo update' to install.",
                )
                EXIT_UPDATE_AVAILABLE
            }
            is UpdateOutcome.Updated -> {
                println("silo ${outcome.from} -> ${outcome.to} installed at ${outcome.installedAt}.")
                println("Restart to apply (e.g. systemctl restart silo, or re-run java -jar silo.jar).")
                EXIT_OK
            }
            is UpdateOutcome.RolledBack -> {
                println("Rolled back to the previous jar at ${outcome.restoredAt}. Restart to apply.")
                EXIT_OK
            }
            is UpdateOutcome.Failed -> {
                System.err.println("Update failed: ${outcome.reason}")
                EXIT_ERROR
            }
        }

    private fun optionValue(
        args: List<String>,
        flag: String,
    ): String? {
        val i = args.indexOf(flag)
        return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
    }

    private fun promptYesNo(message: String): Boolean {
        print(message)
        val line = readlnOrNull()?.trim()?.lowercase() ?: return false
        return line == "y" || line == "yes"
    }
}
