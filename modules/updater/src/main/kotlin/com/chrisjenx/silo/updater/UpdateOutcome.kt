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

import java.nio.file.Path

/** Result of an update attempt. Exhaustive so the CLI maps each case to an exit code + message. */
sealed interface UpdateOutcome {
    data class UpToDate(val current: SemVer) : UpdateOutcome
    data class UpdateAvailable(val current: SemVer, val latest: SemVer) : UpdateOutcome
    data class Updated(val from: SemVer, val to: SemVer, val installedAt: Path) : UpdateOutcome
    data class RolledBack(val restoredAt: Path) : UpdateOutcome
    data class Failed(val reason: String) : UpdateOutcome
}

/** Everything the orchestrator needs to run one update. */
data class UpdateRequest(
    val currentVersion: String,
    val toTag: String? = null,
    val checkOnly: Boolean = false,
    val includePrerelease: Boolean = false,
    val verifyAttestation: Boolean = true,
    val rollback: Boolean = false,
)
