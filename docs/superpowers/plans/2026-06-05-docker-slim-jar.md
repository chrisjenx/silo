# Full vs Slim Jar (drop the self-updater from Docker) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce two fat jars — a **full** jar (`silo-<v>-all.jar`, with `silo update`) for standalone users and a **slim** jar (`silo-<v>-slim.jar`, no `:updater`/sigstore) for the Docker image — by making the `update` verb a compile-time-wired `Subcommand` plugin.

**Architecture:** `:server` loses its `:updater` dependency and gains a `Subcommand` SPI + a shared `runCli(args, extra)` dispatcher; it builds the slim jar. A new `:server-update` module (depends on `:server` + `:updater`) holds `UpdateSubcommand` and a full `main()` that wires it explicitly; it builds the full jar. No ServiceLoader, no reflection, no DI framework.

**Tech Stack:** Kotlin 2.2 / JVM 21, Gradle (Kotlin DSL, buildSrc convention plugins), Shadow fat-jars, kotest 6 (StringSpec, unit/integration split), ktlint + detekt + spotless, CycloneDX, GitHub Actions.

**Reference spec:** `docs/superpowers/specs/2026-06-05-docker-slim-jar-design.md`

---

## Conventions for every task

- **License header:** spotless enforces the Apache header on every `.kt`. Don't hand-paste — run `./gradlew :<module>:spotlessApply` before committing (code blocks below omit it).
- **Formatting + lint gate:** after writing code run `./gradlew :<module>:ktlintFormat`, then confirm the FULL `./gradlew :<module>:check` is BUILD SUCCESSFUL (detekt + ktlint + unitTest + integrationTest; `:server`/`:server-update` have no Kover gate). `spotlessApply` only stamps the license header — it does NOT run ktlint.
- **detekt** blocks `!!` and also flags `DestructuringDeclarationWithTooManyEntries` (max 3), return-count/complexity, `LongParameterList`, `TooGenericExceptionCaught` — refactor or add an inline `@Suppress("RuleName")` with a comment.
- **Single spec:** `./gradlew :<module>:unitTest --tests "*SomeSpec"` (filtering one spec may emit a spurious initializationError for siblings — trust the full `:unitTest` run).
- **Commit** after each task with a Conventional Commit; end the body with:
  `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`
- Build commands may be intercepted by a context-mode hook asking you to run them via a tool and print only the tail — follow it; the goal is pass/fail visibility.
- **Branch:** this work is a separate PR stacked on PR #145. It must be based on #145's branch HEAD (`worktree-nested-kindling-scott`). Do NOT start from `main` — the code being refactored only exists on #145.

---

## File Structure

```
settings.gradle.kts                              MODIFY  include ":server-update"
modules/server-update/build.gradle.kts           CREATE  full-jar module (depends on :server + :updater)
modules/server-update/src/main/kotlin/com/chrisjenx/silo/serverupdate/
  UpdateSubcommand.kt                             CREATE  (moved from :server/UpdateCommand.kt) Subcommand impl
  Main.kt                                         CREATE  full-jar entry; wires listOf(UpdateSubcommand)
modules/server-update/src/unitTest/kotlin/com/chrisjenx/silo/serverupdate/
  UpdateSubcommandSpec.kt                         CREATE  (moved from :server/UpdateCommandSpec.kt)
modules/server/src/main/kotlin/com/chrisjenx/silo/server/
  Subcommand.kt                                   CREATE  SPI interface
  Cli.kt                                          CREATE  runCli(args, extra) + startServer() + redirect
  Main.kt                                         MODIFY  slim entry: runCli(args, emptyList()) then startServer()
  UpdateCommand.kt                                DELETE  (moved to :server-update)
modules/server/src/unitTest/kotlin/com/chrisjenx/silo/server/
  RunCliSpec.kt                                   CREATE  dispatch + redirect tests
  UpdateCommandSpec.kt                            DELETE  (moved to :server-update)
modules/server/build.gradle.kts                   MODIFY  drop :updater dep + cyclonedx; shadowJar classifier "slim"
Dockerfile                                         MODIFY  build/copy the slim jar
.github/workflows/release.yml                      MODIFY  build :server-update (full jar) + its SBOM
.github/workflows/ci.yml                           MODIFY  guard: slim jar excludes updater/sigstore; full includes
docs/operations.md, CLAUDE.md                      MODIFY  document the slim redirect + which task builds which jar
```

---

## Task 1: Scaffold the `:server-update` module

**Files:**
- Modify: `settings.gradle.kts`
- Create: `modules/server-update/build.gradle.kts`

- [ ] **Step 1: Register the module in `settings.gradle.kts`**

Add `":server-update",` to the `include(...)` list (after `":server",`) and the projectDir mapping:
```kotlin
include(
    ":protocol",
    ":storage",
    ":storage-fs",
    ":metadata-sqlite",
    ":metrics",
    ":updater",
    ":server",
    ":server-update",
    ":test-fixtures",
    ":bench",
)
// ...
project(":server-update").projectDir = file("modules/server-update")
```

- [ ] **Step 2: Create `modules/server-update/build.gradle.kts`**

```kotlin
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("silo.testing-conventions")
    alias(libs.plugins.shadow)
    alias(libs.plugins.cyclonedx)
}

base {
    // The full distributable: silo-<version>-all.jar (server + the update plugin).
    archivesName.set("silo")
}

// Short commit SHA embedded in the jar manifest (mirrors :server).
val commitSha: String =
    (project.findProperty("silo.commit") as String?)?.takeIf { it.isNotBlank() }
        ?: runCatching {
            providers.exec {
                commandLine("git", "rev-parse", "--short", "HEAD")
                isIgnoreExitValue = true
            }.standardOutput.asText.get().trim()
        }.getOrNull()?.takeIf { it.isNotEmpty() }
        ?: "unknown"

tasks.named<ShadowJar>("shadowJar") {
    // Default classifier "all" → silo-<version>-all.jar (what release.yml globs).
    manifest {
        attributes["Main-Class"] = "com.chrisjenx.silo.serverupdate.Main"
        attributes["Implementation-Title"] = "silo"
        attributes["Implementation-Version"] = project.version.toString()
        attributes["Implementation-SHA"] = commitSha
    }
    mergeServiceFiles()
}

dependencies {
    implementation(project(":server"))
    implementation(project(":updater"))

    "unitTestImplementation"(project(":test-fixtures"))
    "integrationTestImplementation"(project(":test-fixtures"))
}
```

- [ ] **Step 3: Verify the module resolves and compiles (no sources yet)**

Run: `./gradlew :server-update:compileKotlin --console=plain`
Expected: BUILD SUCCESSFUL (compileKotlin is NO-SOURCE; the point is the module is wired into the build and its dependencies resolve).

- [ ] **Step 4: Commit**
```bash
git add settings.gradle.kts modules/server-update
git commit -m "build: scaffold :server-update module (full-jar distribution)"
```

---

## Task 2: Decouple `update` into a `Subcommand` plugin

This is the core refactor. It leaves both modules compiling and tested; the full-jar wiring lives in `:server-update`, the slim path in `:server`. (The fat-jar *artifacts* are wired in Task 3.)

**Files:**
- Create: `modules/server/src/main/kotlin/com/chrisjenx/silo/server/Subcommand.kt`
- Create: `modules/server/src/main/kotlin/com/chrisjenx/silo/server/Cli.kt`
- Modify: `modules/server/src/main/kotlin/com/chrisjenx/silo/server/Main.kt`
- Modify: `modules/server/build.gradle.kts` (drop `:updater`)
- Delete: `modules/server/src/main/kotlin/com/chrisjenx/silo/server/UpdateCommand.kt`
- Delete: `modules/server/src/unitTest/kotlin/com/chrisjenx/silo/server/UpdateCommandSpec.kt`
- Create: `modules/server-update/src/main/kotlin/com/chrisjenx/silo/serverupdate/UpdateSubcommand.kt`
- Create: `modules/server-update/src/main/kotlin/com/chrisjenx/silo/serverupdate/Main.kt`
- Create: `modules/server-update/src/unitTest/kotlin/com/chrisjenx/silo/serverupdate/UpdateSubcommandSpec.kt`
- Create: `modules/server/src/unitTest/kotlin/com/chrisjenx/silo/server/RunCliSpec.kt`

- [ ] **Step 1: Write the failing `RunCliSpec.kt` (in `:server`)**

```kotlin
package com.chrisjenx.silo.server

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

private class FakeSubcommand(override val name: String, private val code: Int) : Subcommand {
    var ranWith: List<String>? = null
    override fun run(args: List<String>): Int {
        ranWith = args
        return code
    }
}

class RunCliSpec : StringSpec({
    "routes a matching subcommand and returns its exit code" {
        val fake = FakeSubcommand("update", 7)
        runCli(arrayOf("update", "--check"), listOf(fake)) shouldBe 7
        fake.ranWith shouldBe listOf("--check")
    }
    "returns null (start server) when nothing matches" {
        runCli(arrayOf(), emptyList()) shouldBe null
    }
    "prints a redirect and returns 1 for a reserved verb that isn't bundled" {
        runCli(arrayOf("update"), emptyList()) shouldBe 1
    }
    "--version returns 0" {
        runCli(arrayOf("--version"), emptyList()) shouldBe 0
    }
})
```

- [ ] **Step 2: Run, expect FAIL** — `./gradlew :server:unitTest --tests "*RunCliSpec"` → fails (`Subcommand`/`runCli` unresolved).

- [ ] **Step 3: Create `Subcommand.kt`**

```kotlin
package com.chrisjenx.silo.server

/** A CLI verb. Built-ins live in :server; optional ones (e.g. `update`) are wired per build. */
interface Subcommand {
    val name: String
    fun run(args: List<String>): Int
}
```

- [ ] **Step 4: Create `Cli.kt`** (shared dispatcher + server start, both `public` so `:server-update` can call them)

```kotlin
package com.chrisjenx.silo.server

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

/** Verbs supplied by an optional plugin module that may be absent from a given build. */
private val RESERVED_PLUGIN_VERBS = setOf("update")

/**
 * Handles CLI subcommands. Returns the process exit code to use, or null to mean
 * "no subcommand matched — start the server". [extra] are build-specific plugin
 * subcommands (e.g. `update` in the full jar; empty in the slim Docker jar).
 */
fun runCli(args: Array<String>, extra: List<Subcommand>): Int? {
    if (args.any { it == "--version" || it == "-V" }) {
        println(SiloVersion.line())
        return 0
    }
    if (args.firstOrNull() == "hash-password") {
        return runHashPassword()
    }
    val verb = args.firstOrNull()
    extra.firstOrNull { it.name == verb }?.let { return it.run(args.drop(1)) }
    if (verb != null && verb in RESERVED_PLUGIN_VERBS) {
        System.err.println(notBundledMessage(verb))
        return 1
    }
    return null
}

private fun notBundledMessage(verb: String): String =
    "'$verb' isn't bundled in this build (e.g. the Docker image). " +
        "Update by pulling a new image — docker pull ghcr.io/chrisjenx/silo:<version> — see docs/operations.md."

/** Starts the Ktor server (blocks). Shared by the slim and full entry points. */
fun startServer() {
    val config = SiloConfig.load(com.typesafe.config.ConfigFactory.load())
    embeddedServer(
        factory = Netty,
        port = config.port,
        host = config.host,
        module = { module() },
    ).start(wait = true)
}
```

- [ ] **Step 5: Rewrite `:server` `Main.kt`** (slim entry — no updater)

```kotlin
@file:JvmName("Main")

package com.chrisjenx.silo.server

import kotlin.system.exitProcess

/**
 * Slim entry point (Docker / no self-update). Run with `./gradlew :server:run` or
 * `java -jar silo-<v>-slim.jar`. Handles built-in verbs (`--version`, `hash-password`)
 * via [runCli]; `update` prints a redirect (it's only bundled in the full jar). Otherwise
 * starts the server.
 */
fun main(args: Array<String>) {
    runCli(args, extra = emptyList())?.let { exitProcess(it) }
    startServer()
}
```

- [ ] **Step 6: Drop the `:updater` dependency from `:server`**

In `modules/server/build.gradle.kts`, delete the line:
```kotlin
    implementation(project(":updater"))
```

- [ ] **Step 7: Move `UpdateCommand` → `:server-update` as `UpdateSubcommand`**

`git mv modules/server/src/main/kotlin/com/chrisjenx/silo/server/UpdateCommand.kt modules/server-update/src/main/kotlin/com/chrisjenx/silo/serverupdate/UpdateSubcommand.kt`, then edit the moved file:
- change the package to `package com.chrisjenx.silo.serverupdate`
- add `import com.chrisjenx.silo.server.Subcommand` and `import com.chrisjenx.silo.server.SiloVersion`
- change `object UpdateCommand {` → `object UpdateSubcommand : Subcommand {`
- add the interface members at the top of the object:
  ```kotlin
      override val name: String = "update"
  ```
- rename the single-arg entry `fun run(args: List<String>): Int` → `override fun run(args: List<String>): Int` (its body, the `--help`/USAGE handling, and the `@Suppress("LongParameterList")` testable overload all stay unchanged).
- The references to `SiloVersion.version` and `com.chrisjenx.silo.updater.*` now resolve via `:server-update`'s deps.

- [ ] **Step 8: Create `:server-update` `Main.kt`** (full entry — wires the plugin explicitly)

```kotlin
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
```

- [ ] **Step 9: Move the spec → `:server-update`**

`git mv modules/server/src/unitTest/kotlin/com/chrisjenx/silo/server/UpdateCommandSpec.kt modules/server-update/src/unitTest/kotlin/com/chrisjenx/silo/serverupdate/UpdateSubcommandSpec.kt`, then edit:
- package → `com.chrisjenx.silo.serverupdate`
- add `import com.chrisjenx.silo.updater.SemVer`, `import com.chrisjenx.silo.updater.UpdateOutcome`, `import com.chrisjenx.silo.updater.UpdateRequest`
- replace `UpdateCommand.run(` → `UpdateSubcommand.run(` (both call sites)
- rename the class `UpdateCommandSpec` → `UpdateSubcommandSpec`
- add a case:
  ```kotlin
      "name is 'update'" {
          UpdateSubcommand.name shouldBe "update"
      }
  ```

- [ ] **Step 10: Format + verify both modules**

Run: `./gradlew :server:ktlintFormat :server-update:ktlintFormat`
Run: `./gradlew :server:check :server-update:check --console=plain`
Expected: BUILD SUCCESSFUL. `:server` no longer references `:updater`; `RunCliSpec` passes; `:server-update` `UpdateSubcommandSpec` passes (its tests are the relocated UpdateCommand tests).

- [ ] **Step 11: Commit**
```bash
./gradlew :server:spotlessApply :server-update:spotlessApply
git add -A modules/server modules/server-update
git commit -m "refactor: make 'update' a Subcommand plugin; :server drops the :updater dep"
```

---

## Task 3: Wire the two shadow jars (slim from `:server`, full from `:server-update`)

**Files:**
- Modify: `modules/server/build.gradle.kts` (shadowJar classifier `slim`; drop cyclonedx)

- [ ] **Step 1: Make `:server:shadowJar` produce the slim jar**

In `modules/server/build.gradle.kts`:
- remove `alias(libs.plugins.cyclonedx)` from the `plugins {}` block (the SBOM is generated for the full jar in `:server-update`).
- update the `base {}` comment and add a classifier to the shadowJar task so it emits `silo-<v>-slim.jar`:
```kotlin
base {
    // The slim distributable (Docker): silo-<version>-slim.jar — no :updater/sigstore.
    archivesName.set("silo")
}
```
```kotlin
tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("slim")
    manifest {
        attributes["Main-Class"] = "com.chrisjenx.silo.server.Main"
        attributes["Implementation-Title"] = "silo"
        attributes["Implementation-Version"] = project.version.toString()
        attributes["Implementation-SHA"] = commitSha
    }
    mergeServiceFiles()
}
```
(Keep the existing `commitSha` block.)

- [ ] **Step 2: Build both jars**

Run: `./gradlew :server:shadowJar :server-update:shadowJar -Psilo.commit=test --console=plain`
Expected: BUILD SUCCESSFUL, producing:
- `modules/server/build/libs/silo-<version>-slim.jar`
- `modules/server-update/build/libs/silo-<version>-all.jar`

Verify with: `ls modules/server/build/libs/silo-*-slim.jar modules/server-update/build/libs/silo-*-all.jar`

- [ ] **Step 3: Smoke-test both jars end to end**

```bash
FULL=$(ls modules/server-update/build/libs/silo-*-all.jar)
SLIM=$(ls modules/server/build/libs/silo-*-slim.jar)
java -jar "$FULL" update --check ; echo "full_update_exit=$?"     # expect a version line, exit 0 or 10
java -jar "$SLIM" update         ; echo "slim_update_exit=$?"     # expect the redirect message, exit 1
java -jar "$SLIM" --version      ; echo "slim_version_exit=$?"    # expect 'silo <v> ...', exit 0
```
Expected: full jar runs the real update check; slim jar prints `'update' isn't bundled … docker pull …` and exits 1; `--version` works on the slim jar.

- [ ] **Step 4: Confirm the slim jar really excludes the updater**

```bash
SLIM=$(ls modules/server/build/libs/silo-*-slim.jar)
FULL=$(ls modules/server-update/build/libs/silo-*-all.jar)
unzip -l "$SLIM" | grep -E 'com/chrisjenx/silo/updater/|dev/sigstore/|io/grpc/' && echo "LEAK" || echo "slim clean"
unzip -l "$FULL" | grep -q 'com/chrisjenx/silo/updater/' && echo "full has updater" || echo "MISSING"
```
Expected: `slim clean` and `full has updater`. (Also note the size difference — slim ~40 MB vs full ~72 MB.)

- [ ] **Step 5: Commit**
```bash
./gradlew :server:spotlessApply
git add modules/server/build.gradle.kts
git commit -m "build: :server builds the slim jar; :server-update builds the full jar"
```

---

## Task 4: Point the Dockerfile at the slim jar

**Files:**
- Modify: `Dockerfile`

- [ ] **Step 1: Build and copy the slim jar**

In `Dockerfile`, change the build stage from:
```dockerfile
RUN --mount=type=cache,target=/home/gradle/.gradle \
    ./gradlew --no-daemon :server:shadowJar -x test
RUN cp modules/server/build/libs/silo-*-all.jar /tmp/silo.jar
```
to:
```dockerfile
RUN --mount=type=cache,target=/home/gradle/.gradle \
    ./gradlew --no-daemon :server:shadowJar -x test
RUN cp modules/server/build/libs/silo-*-slim.jar /tmp/silo.jar
```
(The `:server:shadowJar` task now emits the `-slim` classifier, so only the `cp` glob changes.)

- [ ] **Step 2: Build the image and smoke-test it**

Run: `docker build -t silo:slim-test .`
Then: `docker run --rm silo:slim-test sh -c 'java -jar /app/silo.jar --version'`
Expected: prints `silo <version> ...`. (Optionally `... update` → prints the redirect, exit 1.)
If Docker isn't available in the environment, note that and rely on Task 6's CI guard + the local jar checks from Task 3.

- [ ] **Step 3: Commit**
```bash
git add Dockerfile
git commit -m "build(docker): ship the slim jar (no self-updater) in the image"
```

---

## Task 5: Point `release.yml` at the full jar + its SBOM

**Files:**
- Modify: `.github/workflows/release.yml`

- [ ] **Step 1: Build the full jar and its SBOM from `:server-update`**

In `.github/workflows/release.yml`:
- change the "Build fat jar" step:
  ```yaml
        run: ./gradlew --no-daemon :server-update:shadowJar -Psilo.commit="${GITHUB_SHA:0:7}"
  ```
- change the "Generate SBOM (CycloneDX)" step:
  ```yaml
        run: ./gradlew --no-daemon :server-update:cyclonedxBom
  ```
- in the "Compute checksums" step, change the two copy lines:
  ```yaml
          cp modules/server-update/build/libs/silo-*-all.jar dist/silo.jar
          cp modules/server-update/build/reports/bom.json dist/silo-sbom.cdx.json
  ```
(Everything downstream — `dist/silo.jar`, checksums, attestation `subject-path: dist/silo.jar`, the release upload, and the Docker job — is unchanged. The published `silo.jar` asset stays the full jar.)

- [ ] **Step 2: Sanity-check the workflow references the new paths**

Run: `grep -n "server-update\|silo-\*-all\|silo-\*-slim" .github/workflows/release.yml`
Expected: the build/SBOM/copy lines reference `:server-update` and `silo-*-all.jar`; no `:server:shadowJar` remains in release.yml.

- [ ] **Step 3: Commit**
```bash
git add .github/workflows/release.yml
git commit -m "ci(release): publish the full jar (silo-*-all) from :server-update"
```

---

## Task 6: CI guard so the split can't silently regress

**Files:**
- Modify: `.github/workflows/ci.yml`

- [ ] **Step 1: Add a jar-content guard step to the `build` job**

In `.github/workflows/ci.yml`, add this step to the `build` job, immediately AFTER the "Build + test + lint + coverage" step:
```yaml
      - name: Verify the slim jar excludes the self-updater
        run: |
          ./gradlew --no-daemon :server:shadowJar :server-update:shadowJar -Psilo.commit="${GITHUB_SHA:0:7}"
          slim=$(ls modules/server/build/libs/silo-*-slim.jar)
          full=$(ls modules/server-update/build/libs/silo-*-all.jar)
          echo "slim=$slim ($(du -h "$slim" | cut -f1))  full=$full ($(du -h "$full" | cut -f1))"
          if unzip -l "$slim" | grep -qE 'com/chrisjenx/silo/updater/|dev/sigstore/|io/grpc/'; then
            echo "::error::slim jar unexpectedly bundles the updater/sigstore"; exit 1
          fi
          if ! unzip -l "$full" | grep -q 'com/chrisjenx/silo/updater/'; then
            echo "::error::full jar is missing the updater"; exit 1
          fi
          echo "Jar split verified: slim excludes the updater, full includes it."
```

- [ ] **Step 2: Validate the workflow YAML**

Run: `python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/ci.yml')); print('ci.yml OK')"`
Expected: `ci.yml OK`. (Also re-run the local equivalent from Task 3 Step 4 to confirm the grep logic matches the real jars.)

- [ ] **Step 3: Commit**
```bash
git add .github/workflows/ci.yml
git commit -m "ci: guard that the slim jar excludes the self-updater"
```

---

## Task 7: Documentation

**Files:**
- Modify: `docs/operations.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: Note the container behavior in `docs/operations.md`**

In the "Updating" section's Docker paragraph, append a sentence:
```markdown
The Docker image ships the **slim** jar, which omits the self-updater entirely — running `silo update` there prints a redirect and exits non-zero. Standalone (`silo.jar`) downloads include it.
```

- [ ] **Step 2: Update the build/commands notes in `CLAUDE.md`**

- In the "Module layout" code block, add the new module line:
  ```
  :server-update     - full-jar assembly: :server + the `update` Subcommand plugin (:updater)
  ```
  and add a sentence after the existing convention-plugins paragraph:
  ```markdown
  Two fat jars: `:server-update:shadowJar` is the **full** distributable (`silo-<v>-all.jar`, the release asset, includes `silo update`); `:server:shadowJar` is the **slim** jar (`silo-<v>-slim.jar`, shipped in the Docker image, no `:updater`/sigstore). `silo update` in the slim jar prints a redirect.
  ```
- In the "Commands" section, update the shadowJar line:
  ```
  ./gradlew :server-update:shadowJar             # FULL fat jar (release/standalone) -> modules/server-update/build/libs/silo-*-all.jar
  ./gradlew :server:shadowJar                     # SLIM fat jar (Docker, no updater) -> modules/server/build/libs/silo-*-slim.jar
  ```

- [ ] **Step 3: Commit**
```bash
git add docs/operations.md CLAUDE.md
git commit -m "docs: document the full vs slim jar split"
```

---

## Task 8: Final whole-project verification

- [ ] **Step 1: Whole-project gate**

Run: `./gradlew clean check --console=plain`
Expected: BUILD SUCCESSFUL (all modules incl. `:server` `RunCliSpec` and `:server-update` `UpdateSubcommandSpec`; networked Sigstore integration test in `:updater` still passes).

- [ ] **Step 2: Both jars build + behave**

Run:
```bash
./gradlew :server:shadowJar :server-update:shadowJar -Psilo.commit=verify --console=plain
FULL=$(ls modules/server-update/build/libs/silo-*-all.jar); SLIM=$(ls modules/server/build/libs/silo-*-slim.jar)
java -jar "$FULL" update --check; echo "full=$?"   # 0 or 10
java -jar "$SLIM" update;         echo "slim=$?"   # redirect, 1
unzip -l "$SLIM" | grep -cE 'com/chrisjenx/silo/updater/|dev/sigstore/' | xargs echo "slim updater/sigstore entries (want 0):"
```
Expected: full does a real update check; slim redirects (exit 1); slim has 0 updater/sigstore entries.

- [ ] **Step 3: Confirm no forbidden deps regressed in the slim runtime**

Run: `./gradlew :server:dependencies --configuration runtimeClasspath --console=plain | grep -ciE "sigstore|grpc" | xargs echo "slim sigstore/grpc refs (want 0):"`
Expected: `0`. (`:server` no longer pulls `:updater`, so sigstore/gRPC are gone from the slim runtime.)

- [ ] **Step 4: Open the PR (stacked on #145)**

Push the branch and open a PR with base = `worktree-nested-kindling-scott` (so the diff is just this work) — or base `main` noting it's stacked on #145 if your tooling prefers. Title: `build: split full (standalone) vs slim (Docker) jars (#144 follow-up)`. Summarize: slim Docker image (~32 MB smaller + reduced CVE surface), explicit compile-time `Subcommand` wiring, `silo update` redirect in containers, CI guard.

---

## Self-Review

**1. Spec coverage**

| Spec requirement | Task(s) |
|---|---|
| Slim jar excludes `:updater`/sigstore (Docker) | 2 (drop dep), 3 (slim shadowJar), 4 (Dockerfile), 6 (CI guard) |
| Full jar keeps self-update (standalone/release) | 1, 2 (`:server-update` + `UpdateSubcommand`), 3 (full shadowJar), 5 (release.yml) |
| `silo update` in slim → friendly redirect, non-zero | 2 (`runCli` + `RESERVED_PLUGIN_VERBS` + `notBundledMessage`), 3 Step 3 smoke |
| Compile-time wiring, no ServiceLoader/reflection/DI | 2 (`Subcommand` + explicit `extra` lists in each `Main`) |
| `:server-update` module (depends on :server + :updater) | 1 |
| Both jars set manifest / SiloVersion works | 1 (full manifest), 3 (slim manifest) |
| SBOM on the full (published) jar | 1 (cyclonedx on :server-update), 5 (release.yml) |
| Dockerfile → slim; release.yml → full | 4, 5 |
| CI guard against regression | 6 |
| Docs (operations + CLAUDE.md) | 7 |
| Stacked on #145 | Conventions header + Task 8 Step 4 |

No uncovered requirements.

**2. Placeholder scan** — no TBD/TODO. The two file moves (Task 2 Steps 7 & 9) transform existing committed code (`UpdateCommand.kt`/`UpdateCommandSpec.kt`) with exact, enumerated edits (package, imports, object name, `override`, `name`), not "implement later". All new code blocks are complete.

**3. Type consistency** — `Subcommand` (`val name: String` + `fun run(args: List<String>): Int`) is defined in Task 2 Step 3 and implemented identically by `FakeSubcommand` (Step 1) and `UpdateSubcommand` (Step 7). `runCli(args: Array<String>, extra: List<Subcommand>): Int?` and `startServer()` are defined in Task 2 Step 4 and called with matching signatures by both `Main`s (Steps 5, 8). `UpdateSubcommand` is an `object` (matching the moved `object UpdateCommand`), so `listOf(UpdateSubcommand)` and `UpdateSubcommand.name`/`.run(...)` are consistent across Tasks 2, 8. Jar names (`silo-*-slim.jar` from `:server`, `silo-*-all.jar` from `:server-update`) are used consistently in Tasks 3–6, 8.
