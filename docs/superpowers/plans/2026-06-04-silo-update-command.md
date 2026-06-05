# `silo update` Self-Update Command — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a built-in `silo update` subcommand that upgrades a directly-run fat jar in place — checking GitHub Releases, verifying the downloaded jar (SHA-256 + GitHub build-provenance attestation), atomically swapping it, and printing restart instructions.

**Architecture:** A new pure-Kotlin `:updater` module split into small single-responsibility units behind interfaces (`ReleaseClient`, `AttestationVerifier`, `JarLocator`, `AtomicJarReplacer`, `ChecksumVerifier`, `SemVer`, `Updater`). `:server` depends on `:updater`; `Main.kt` dispatches the `update` verb to an `UpdateCommand`. Networking uses the JDK `java.net.http.HttpClient`; JSON uses `kotlinx-serialization`; provenance is verified in-JVM with `sigstore-java`.

**Tech Stack:** Kotlin 2.2 / JVM 21, Gradle (Kotlin DSL, buildSrc convention plugins), kotest 6 (`StringSpec`, unit/integration source-set split), Kover (80% line gate), ktlint + detekt + spotless (Apache license header enforced), `kotlinx-serialization-json`, `dev.sigstore:sigstore-java`.

**Reference spec:** `docs/superpowers/specs/2026-06-04-auto-update-design.md`

---

## Conventions for every task

- **License header:** spotless enforces the Apache header on every `.kt` file. Don't hand-paste it — after creating/editing files run `./gradlew :updater:spotlessApply` (and `:server:spotlessApply` for Task 13) before committing. Code blocks below omit the header for brevity.
- **Test layout:** unit tests live in `modules/updater/src/unitTest/kotlin/com/chrisjenx/silo/updater/`, integration tests in `…/src/integrationTest/kotlin/…`. Specs are `*Spec.kt`, kotest `StringSpec`.
- **Run a single unit spec:** `./gradlew :updater:unitTest --tests "com.chrisjenx.silo.updater.<SpecName>"`
- **Run the module's full gate:** `./gradlew :updater:check` (runs `unitTest`, `integrationTest`, `koverVerify`, ktlint, detekt).
- **No `!!`** (detekt blocks it). No `runBlocking` outside `main`/tests. Use `Result`/sealed types for failures, not silent catches.
- **Commit** after each task with a Conventional Commit message (`feat:`, `test:`, `build:`, `docs:`). The repo uses commitlint.

---

## File Structure

```
gradle/libs.versions.toml                 MODIFY  add sigstore-java lib + version
settings.gradle.kts                        MODIFY  include ":updater"
modules/updater/build.gradle.kts           CREATE  module build (kotlin + serialization + coverage + testing)
modules/updater/src/main/kotlin/com/chrisjenx/silo/updater/
  SemVer.kt                                CREATE  parse + compare semantic versions
  ChecksumVerifier.kt                      CREATE  SHA-256 of a file vs a checksums.txt entry
  UpdateOutcome.kt                         CREATE  sealed result type + UpdateRequest
  Release.kt                               CREATE  Release / ReleaseAsset domain + GitHub DTOs
  ReleaseClient.kt                         CREATE  interface
  GitHubReleaseClient.kt                   CREATE  JDK HttpClient impl
  JarLocator.kt                            CREATE  resolve running jar; dev/exploded/not-writable guards
  AtomicJarReplacer.kt                     CREATE  temp->final ATOMIC_MOVE + .bak + fallback + rollback
  AttestationVerifier.kt                   CREATE  interface
  SigstoreAttestationVerifier.kt           CREATE  in-JVM sigstore-java impl + identity policy
  Updater.kt                               CREATE  orchestrator
modules/server/src/main/kotlin/com/chrisjenx/silo/server/
  UpdateCommand.kt                         CREATE  flag parsing, stdout, outcome->exit code
  Main.kt                                  MODIFY  dispatch "update" verb
modules/server/build.gradle.kts            MODIFY  implementation(project(":updater"))
modules/updater/src/unitTest/kotlin/com/chrisjenx/silo/updater/
  SemVerSpec.kt, ChecksumVerifierSpec.kt, JarLocatorSpec.kt,
  AtomicJarReplacerSpec.kt, GitHubReleaseClientSpec.kt, UpdaterSpec.kt
modules/updater/src/integrationTest/kotlin/com/chrisjenx/silo/updater/
  SigstoreAttestationVerifierSpec.kt
modules/server/src/unitTest/kotlin/com/chrisjenx/silo/server/
  UpdateCommandSpec.kt
docs/operations.md, docs/configuration.md, README.md   MODIFY  document the command
```

---

## Task 1: Scaffold the `:updater` module

**Files:**
- Modify: `settings.gradle.kts`
- Create: `modules/updater/build.gradle.kts`
- Create + Test: `modules/updater/src/unitTest/kotlin/com/chrisjenx/silo/updater/SemVerSpec.kt` (smoke)
- Create: `modules/updater/src/main/kotlin/com/chrisjenx/silo/updater/SemVer.kt` (stub for smoke)

- [ ] **Step 1: Register the module in `settings.gradle.kts`**

Add `":updater",` to the `include(...)` list (after `":metrics",`) and the projectDir mapping next to the others:

```kotlin
include(
    ":protocol",
    ":storage",
    ":storage-fs",
    ":metadata-sqlite",
    ":metrics",
    ":updater",
    ":server",
    ":test-fixtures",
    ":bench",
)
// ...
project(":updater").projectDir = file("modules/updater")
```

- [ ] **Step 2: Create `modules/updater/build.gradle.kts`**

```kotlin
plugins {
    id("silo.coverage-conventions")
    id("silo.testing-conventions")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.sigstore.java)

    "unitTestImplementation"(libs.mockk)
}
```

(`libs.sigstore.java` is added in Step 4; `libs.mockk` and `libs.kotlinx.serialization.json` already exist in the catalog.)

- [ ] **Step 3: Create a smoke test `SemVerSpec.kt`**

```kotlin
package com.chrisjenx.silo.updater

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class SemVerSpec : StringSpec({
    "module builds and a trivial SemVer holds its parts" {
        SemVer(0, 1, 3).major shouldBe 0
    }
})
```

- [ ] **Step 4: Add the sigstore-java dependency to `gradle/libs.versions.toml`**

Under `[versions]` add (verify/bump the version in Step 6 — Renovate will track it):
```toml
sigstore-java = "1.3.0"
```
Under `[libraries]` add:
```toml
sigstore-java = { module = "dev.sigstore:sigstore-java", version.ref = "sigstore-java" }
```

- [ ] **Step 5: Create the stub `SemVer.kt`**

```kotlin
package com.chrisjenx.silo.updater

data class SemVer(val major: Int, val minor: Int, val patch: Int, val preRelease: String? = null)
```

- [ ] **Step 6: Build the module and confirm sigstore-java resolves**

Run: `./gradlew :updater:unitTest --tests "com.chrisjenx.silo.updater.SemVerSpec"`
Expected: PASS. If sigstore-java `1.3.0` fails to resolve, run `./gradlew :updater:dependencies --configuration runtimeClasspath` to find the latest published version and update the catalog. **Also confirm it does NOT pull `com.fasterxml.jackson` (forbidden):** `./gradlew :updater:dependencies --configuration runtimeClasspath | grep -i jackson` must print nothing. If Jackson appears, stop and revisit the `AttestationVerifier` approach (see spec "Risks").

- [ ] **Step 7: Apply license headers + commit**

```bash
./gradlew :updater:spotlessApply
git add settings.gradle.kts gradle/libs.versions.toml modules/updater
git commit -m "build: scaffold :updater module with sigstore-java + kotlinx-serialization"
```

---

## Task 2: `SemVer` parse + compare

**Files:**
- Modify: `modules/updater/src/main/kotlin/com/chrisjenx/silo/updater/SemVer.kt`
- Test: `modules/updater/src/unitTest/kotlin/com/chrisjenx/silo/updater/SemVerSpec.kt`

- [ ] **Step 1: Replace `SemVerSpec.kt` with real cases**

```kotlin
package com.chrisjenx.silo.updater

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe

class SemVerSpec : StringSpec({
    "parses plain and v-prefixed versions" {
        SemVer.parse("v0.1.3") shouldBe SemVer(0, 1, 3)
        SemVer.parse("0.2.0") shouldBe SemVer(0, 2, 0)
    }
    "parses a prerelease suffix" {
        SemVer.parse("1.0.0-rc1") shouldBe SemVer(1, 0, 0, "rc1")
    }
    "orders by major, minor, patch" {
        SemVer.parse("0.2.0") shouldBeGreaterThan SemVer.parse("0.1.9")
        SemVer.parse("1.0.0") shouldBeGreaterThan SemVer.parse("0.9.9")
    }
    "a prerelease is lower than its release" {
        SemVer.parse("1.0.0-rc1") shouldBeLessThan SemVer.parse("1.0.0")
    }
    "rejects garbage" {
        runCatching { SemVer.parse("not-a-version") }.isFailure shouldBe true
    }
})
```

- [ ] **Step 2: Run, expect FAIL** — `./gradlew :updater:unitTest --tests "*SemVerSpec"` → fails (`parse` unresolved / `SemVer` not `Comparable`).

- [ ] **Step 3: Implement `SemVer.kt`**

```kotlin
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
            val m = PATTERN.matchEntire(raw.trim())
                ?: throw IllegalArgumentException("Not a semantic version: '$raw'")
            val (maj, min, pat, pre) = m.destructured
            return SemVer(maj.toInt(), min.toInt(), pat.toInt(), pre.ifEmpty { null })
        }
    }
}
```

- [ ] **Step 4: Run, expect PASS** — `./gradlew :updater:unitTest --tests "*SemVerSpec"`

- [ ] **Step 5: Commit**
```bash
./gradlew :updater:spotlessApply
git add modules/updater
git commit -m "feat(updater): SemVer parse and ordering"
```

---

## Task 3: `ChecksumVerifier`

**Files:**
- Create: `modules/updater/src/main/kotlin/com/chrisjenx/silo/updater/ChecksumVerifier.kt`
- Test: `modules/updater/src/unitTest/kotlin/com/chrisjenx/silo/updater/ChecksumVerifierSpec.kt`

`checksums.txt` is GNU `sha256sum` format: `<hex>␠␠<filename>` per line.

- [ ] **Step 1: Write `ChecksumVerifierSpec.kt`**

```kotlin
package com.chrisjenx.silo.updater

import io.kotest.core.spec.style.StringSpec
import io.kotest.engine.spec.tempfile
import io.kotest.matchers.shouldBe
import java.security.MessageDigest

class ChecksumVerifierSpec : StringSpec({

    fun hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    "computes lowercase hex sha-256 of a file" {
        val f = tempfile()
        f.writeBytes("hello".toByteArray())
        ChecksumVerifier.sha256(f.toPath()) shouldBe hex("hello".toByteArray())
    }

    "extracts the expected digest for a named asset" {
        val checksums = "abc123  silo.jar\ndef456  silo-sbom.cdx.json\n"
        ChecksumVerifier.expectedFor("silo.jar", checksums) shouldBe "abc123"
        ChecksumVerifier.expectedFor("missing.bin", checksums) shouldBe null
    }

    "matches returns true only when the file digest equals the checksums entry" {
        val f = tempfile()
        f.writeBytes("payload".toByteArray())
        val good = "${hex("payload".toByteArray())}  silo.jar\n"
        val bad = "0000  silo.jar\n"
        ChecksumVerifier.matches(f.toPath(), "silo.jar", good) shouldBe true
        ChecksumVerifier.matches(f.toPath(), "silo.jar", bad) shouldBe false
    }
})
```

- [ ] **Step 2: Run, expect FAIL** — `./gradlew :updater:unitTest --tests "*ChecksumVerifierSpec"`

- [ ] **Step 3: Implement `ChecksumVerifier.kt`**

```kotlin
package com.chrisjenx.silo.updater

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

object ChecksumVerifier {

    fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read < 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Returns the hex digest listed for [assetName] in `sha256sum`-format text, or null. */
    fun expectedFor(assetName: String, checksumsTxt: String): String? =
        checksumsTxt.lineSequence()
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 2)
                if (parts.size == 2) parts[0] to parts[1].trim() else null
            }
            .firstOrNull { it.second == assetName }
            ?.first

    fun matches(file: Path, assetName: String, checksumsTxt: String): Boolean {
        val expected = expectedFor(assetName, checksumsTxt) ?: return false
        return expected.equals(sha256(file), ignoreCase = true)
    }
}
```

- [ ] **Step 4: Run, expect PASS** — `./gradlew :updater:unitTest --tests "*ChecksumVerifierSpec"`

- [ ] **Step 5: Commit**
```bash
./gradlew :updater:spotlessApply
git add modules/updater
git commit -m "feat(updater): SHA-256 checksum verification against checksums.txt"
```

---

## Task 4: `UpdateOutcome` + `UpdateRequest`

**Files:**
- Create: `modules/updater/src/main/kotlin/com/chrisjenx/silo/updater/UpdateOutcome.kt`
- Test: covered indirectly by `UpdaterSpec` (Task 11). No standalone test for a pure data type (YAGNI), but add a tiny compile-guard spec.

- [ ] **Step 1: Write `UpdateOutcome.kt`**

```kotlin
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
```

- [ ] **Step 2: Add a compile-guard spec `UpdateOutcomeSpec.kt`**

```kotlin
package com.chrisjenx.silo.updater

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class UpdateOutcomeSpec : StringSpec({
    "outcome carries version transition" {
        val o = UpdateOutcome.UpToDate(SemVer(0, 1, 3))
        o.current shouldBe SemVer(0, 1, 3)
    }
})
```

- [ ] **Step 3: Run, expect PASS** — `./gradlew :updater:unitTest --tests "*UpdateOutcomeSpec"`

- [ ] **Step 4: Commit**
```bash
./gradlew :updater:spotlessApply
git add modules/updater
git commit -m "feat(updater): UpdateOutcome and UpdateRequest types"
```

---

## Task 5: `JarLocator`

Resolves the running jar and guards the cases where self-update must refuse.

**Files:**
- Create: `modules/updater/src/main/kotlin/com/chrisjenx/silo/updater/JarLocator.kt`
- Test: `modules/updater/src/unitTest/kotlin/com/chrisjenx/silo/updater/JarLocatorSpec.kt`

- [ ] **Step 1: Write `JarLocatorSpec.kt`**

```kotlin
package com.chrisjenx.silo.updater

import io.kotest.core.spec.style.StringSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class JarLocatorSpec : StringSpec({

    "refuses a dev/unversioned build" {
        val dir = tempdir()
        val jar = dir.resolve("silo.jar").also { it.writeBytes(byteArrayOf(1)) }
        val r = JarLocator.locate(currentVersion = "dev", codeSource = jar.toPath())
        (r as UpdateOutcome.Failed).reason shouldContain "dev"
    }

    "refuses when the code source is a directory (exploded classpath / :run)" {
        val dir = tempdir()
        val r = JarLocator.locate(currentVersion = "0.1.3", codeSource = dir.toPath())
        (r as UpdateOutcome.Failed).reason shouldContain "packaged jar"
    }

    "refuses when the jar's directory is not writable" {
        val dir = tempdir()
        val jar = dir.resolve("silo.jar").also { it.writeBytes(byteArrayOf(1)) }
        dir.setWritable(false)
        try {
            val r = JarLocator.locate(currentVersion = "0.1.3", codeSource = jar.toPath())
            (r as UpdateOutcome.Failed).reason shouldContain "image"
        } finally {
            dir.setWritable(true)
        }
    }

    "resolves a writable packaged jar" {
        val dir = tempdir()
        val jar = dir.resolve("silo.jar").also { it.writeBytes(byteArrayOf(1)) }
        val r = JarLocator.locate(currentVersion = "0.1.3", codeSource = jar.toPath())
        (r as JarLocated).path shouldBe jar.toPath()
    }
})
```

- [ ] **Step 2: Run, expect FAIL** — `./gradlew :updater:unitTest --tests "*JarLocatorSpec"`

- [ ] **Step 3: Implement `JarLocator.kt`**

`locate` returns either a `JarLocated` (a small carrier) or an `UpdateOutcome.Failed`. We use a tiny sealed result so callers stay exhaustive.

```kotlin
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
```

> Note: `locate` returns `Any` (either `JarLocated` or `UpdateOutcome.Failed`). The orchestrator (Task 11) does a `when` on the two concrete types. This keeps `JarLocated` out of the `UpdateOutcome` hierarchy (it isn't a terminal outcome) while staying type-checked at the call site.

- [ ] **Step 4: Run, expect PASS** — `./gradlew :updater:unitTest --tests "*JarLocatorSpec"`
> If the not-writable test is flaky as root (CI sometimes runs as root, which ignores the writable bit), guard it with `io.kotest.core.annotation.EnabledIf` or skip when `System.getProperty("user.name") == "root"`. Add that guard only if CI shows the failure.

- [ ] **Step 5: Commit**
```bash
./gradlew :updater:spotlessApply
git add modules/updater
git commit -m "feat(updater): JarLocator with dev/exploded/not-writable guards"
```

---

## Task 6: `AtomicJarReplacer`

**Files:**
- Create: `modules/updater/src/main/kotlin/com/chrisjenx/silo/updater/AtomicJarReplacer.kt`
- Test: `modules/updater/src/unitTest/kotlin/com/chrisjenx/silo/updater/AtomicJarReplacerSpec.kt`

- [ ] **Step 1: Write `AtomicJarReplacerSpec.kt`**

```kotlin
package com.chrisjenx.silo.updater

import io.kotest.core.spec.style.StringSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class AtomicJarReplacerSpec : StringSpec({

    "replace swaps in the new jar and keeps a .bak of the old one" {
        val dir = tempdir().toPath()
        val jar = dir.resolve("silo.jar").also { Files.writeString(it, "OLD") }
        val incoming = dir.resolve(".silo-update.tmp").also { Files.writeString(it, "NEW") }

        AtomicJarReplacer().replace(jar = jar, verifiedSource = incoming)

        Files.readString(jar) shouldBe "NEW"
        Files.readString(dir.resolve("silo.jar.bak")) shouldBe "OLD"
        Files.exists(incoming) shouldBe false
    }

    "rollback restores the previous jar from .bak" {
        val dir = tempdir().toPath()
        val jar = dir.resolve("silo.jar").also { Files.writeString(it, "OLD") }
        val incoming = dir.resolve(".silo-update.tmp").also { Files.writeString(it, "NEW") }
        val replacer = AtomicJarReplacer()
        replacer.replace(jar = jar, verifiedSource = incoming)

        replacer.rollback(jar) shouldBe true
        Files.readString(jar) shouldBe "OLD"
    }

    "rollback returns false when there is no .bak" {
        val dir = tempdir().toPath()
        val jar = dir.resolve("silo.jar").also { Files.writeString(it, "OLD") }
        AtomicJarReplacer().rollback(jar) shouldBe false
    }
})
```

- [ ] **Step 2: Run, expect FAIL** — `./gradlew :updater:unitTest --tests "*AtomicJarReplacerSpec"`

- [ ] **Step 3: Implement `AtomicJarReplacer.kt`**

```kotlin
package com.chrisjenx.silo.updater

import org.slf4j.LoggerFactory
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Replaces a jar in place with an already-verified source file, mirroring the cache write
 * protocol: fsync the incoming bytes, keep a .bak rollback point, then atomic-rename. Falls
 * back to a non-atomic rename dance (Windows/locked file) with rollback on failure.
 */
class AtomicJarReplacer(private val fsync: Boolean = true) {

    private val log = LoggerFactory.getLogger(AtomicJarReplacer::class.java)

    fun replace(jar: Path, verifiedSource: Path) {
        if (fsync) forceFile(verifiedSource)
        val backup = jar.resolveSibling("${jar.fileName}.bak")
        Files.copy(jar, backup, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
        try {
            Files.move(
                verifiedSource,
                jar,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            log.warn("Atomic move unsupported ({}); using non-atomic replace with rollback.", e.message)
            try {
                Files.move(verifiedSource, jar, StandardCopyOption.REPLACE_EXISTING)
            } catch (t: Exception) {
                Files.copy(backup, jar, StandardCopyOption.REPLACE_EXISTING)
                throw t
            }
        }
        if (fsync) forceDir(jar.toAbsolutePath().parent)
    }

    /** Restores the jar from its .bak sibling. Returns false if no backup exists. */
    fun rollback(jar: Path): Boolean {
        val backup = jar.resolveSibling("${jar.fileName}.bak")
        if (!Files.isRegularFile(backup)) return false
        Files.copy(backup, jar, StandardCopyOption.REPLACE_EXISTING)
        if (fsync) forceDir(jar.toAbsolutePath().parent)
        return true
    }

    private fun forceFile(path: Path) {
        FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE).use { it.force(true) }
    }

    private fun forceDir(dir: Path?) {
        if (dir == null) return
        runCatching {
            FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
        }.onFailure { log.debug("Directory fsync skipped for {}: {}", dir, it.message) }
    }
}
```

> The `slf4j` API is already on the classpath transitively via kotlinx libs? It is **not** — add `implementation(libs.slf4j.api)` to `modules/updater/build.gradle.kts` dependencies in this task, then re-run.

- [ ] **Step 4: Run, expect PASS** — `./gradlew :updater:unitTest --tests "*AtomicJarReplacerSpec"`

- [ ] **Step 5: Commit**
```bash
./gradlew :updater:spotlessApply
git add modules/updater
git commit -m "feat(updater): atomic jar replace with .bak rollback and fsync"
```

---

## Task 7: Release domain + `ReleaseClient` interface + GitHub DTOs

**Files:**
- Create: `modules/updater/src/main/kotlin/com/chrisjenx/silo/updater/Release.kt`
- Create: `modules/updater/src/main/kotlin/com/chrisjenx/silo/updater/ReleaseClient.kt`
- Test: `modules/updater/src/unitTest/kotlin/com/chrisjenx/silo/updater/ReleaseParsingSpec.kt`

- [ ] **Step 1: Write `ReleaseParsingSpec.kt`** (tests JSON → domain mapping)

```kotlin
package com.chrisjenx.silo.updater

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ReleaseParsingSpec : StringSpec({
    val json = """
        {"tag_name":"v0.2.0","prerelease":false,"assets":[
          {"name":"silo.jar","browser_download_url":"https://example/silo.jar"},
          {"name":"checksums.txt","browser_download_url":"https://example/checksums.txt"}
        ]}
    """.trimIndent()

    "maps a GitHub release payload to the domain Release" {
        val r = Release.fromJson(json)
        r.tag shouldBe "v0.2.0"
        r.version shouldBe SemVer(0, 2, 0)
        r.prerelease shouldBe false
        r.asset("silo.jar")?.downloadUrl shouldBe "https://example/silo.jar"
        r.asset("missing") shouldBe null
    }
})
```

- [ ] **Step 2: Run, expect FAIL** — `./gradlew :updater:unitTest --tests "*ReleaseParsingSpec"`

- [ ] **Step 3: Implement `Release.kt`**

```kotlin
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
        fun listFromJson(text: String): List<Release> =
            json.decodeFromString<List<GhRelease>>(text).mapNotNull { it.toDomainOrNull() }
    }
}

@Serializable
private data class GhRelease(
    @SerialName("tag_name") val tagName: String,
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    val assets: List<GhAsset> = emptyList(),
) {
    fun toDomain(): Release =
        Release(tagName, SemVer.parse(tagName), prerelease, assets.map { ReleaseAsset(it.name, it.url) })

    /** Tolerant variant for list endpoints: skip non-semver tags instead of throwing. */
    fun toDomainOrNull(): Release? = runCatching { toDomain() }.getOrNull()
}

@Serializable
private data class GhAsset(
    val name: String,
    @SerialName("browser_download_url") val url: String,
)
```

- [ ] **Step 4: Implement `ReleaseClient.kt`** (interface only — impl in Task 8)

```kotlin
package com.chrisjenx.silo.updater

import java.nio.file.Path

/** Abstraction over the GitHub Releases + attestations API. Faked in unit tests. */
interface ReleaseClient {
    /** Latest stable release (or newest including prereleases when [includePrerelease]). */
    fun latest(includePrerelease: Boolean): Release

    /** A specific release by tag, e.g. "v0.2.0". */
    fun byTag(tag: String): Release

    /** Fetches a small text asset (e.g. checksums.txt) in full. */
    fun fetchText(url: String): String

    /** Streams a binary asset to [dest]. */
    fun download(url: String, dest: Path)

    /** Raw GitHub attestation bundle JSON for a jar's `sha256:<hex>` digest. */
    fun attestationBundle(sha256Hex: String): String
}
```

- [ ] **Step 5: Run, expect PASS** — `./gradlew :updater:unitTest --tests "*ReleaseParsingSpec"`

- [ ] **Step 6: Commit**
```bash
./gradlew :updater:spotlessApply
git add modules/updater
git commit -m "feat(updater): Release domain, JSON mapping, and ReleaseClient interface"
```

---

## Task 8: `GitHubReleaseClient` (JDK HttpClient impl)

**Files:**
- Create: `modules/updater/src/main/kotlin/com/chrisjenx/silo/updater/GitHubReleaseClient.kt`
- Test: `modules/updater/src/unitTest/kotlin/com/chrisjenx/silo/updater/GitHubReleaseClientSpec.kt`

We test against a JDK `com.sun.net.httpserver.HttpServer` loopback instance (no new dep, deterministic). This is a unit-level test of request shaping + response handling.

- [ ] **Step 1: Write `GitHubReleaseClientSpec.kt`**

```kotlin
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
            val body = """{"tag_name":"v0.2.0","prerelease":false,"assets":[
                {"name":"silo.jar","browser_download_url":"http://x/silo.jar"}]}""".trimIndent()
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
```

- [ ] **Step 2: Run, expect FAIL** — `./gradlew :updater:unitTest --tests "*GitHubReleaseClientSpec"`

- [ ] **Step 3: Implement `GitHubReleaseClient.kt`**

```kotlin
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

    override fun byTag(tag: String): Release =
        Release.fromJson(getString("$apiBase/repos/$repo/releases/tags/$tag"))

    override fun fetchText(url: String): String = getString(url)

    override fun download(url: String, dest: Path) {
        val res = http.send(baseRequest(url).GET().build(), HttpResponse.BodyHandlers.ofFile(dest))
        require(res.statusCode() in 200..299) { "Download failed (${res.statusCode()}) for $url" }
    }

    override fun attestationBundle(sha256Hex: String): String =
        getString("$apiBase/repos/$repo/attestations/sha256:$sha256Hex")

    private fun getString(url: String): String {
        val res = http.send(baseRequest(url).GET().build(), HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() == 403 && res.headers().firstValue("x-ratelimit-remaining").orElse("") == "0") {
            error("GitHub API rate limit exceeded. Set SILO_UPDATE_TOKEN to raise the limit.")
        }
        require(res.statusCode() in 200..299) { "GitHub API ${res.statusCode()} for $url: ${res.body().take(200)}" }
        return res.body()
    }

    private fun baseRequest(url: String): HttpRequest.Builder {
        val b = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", userAgent)
            .header("Accept", "application/vnd.github+json")
            .timeout(Duration.ofMinutes(5))
        if (!token.isNullOrBlank()) b.header("Authorization", "Bearer $token")
        return b
    }
}
```

- [ ] **Step 4: Run, expect PASS** — `./gradlew :updater:unitTest --tests "*GitHubReleaseClientSpec"`

- [ ] **Step 5: Commit**
```bash
./gradlew :updater:spotlessApply
git add modules/updater
git commit -m "feat(updater): GitHubReleaseClient over JDK HttpClient"
```

---

## Task 9: `AttestationVerifier` interface + fake

**Files:**
- Create: `modules/updater/src/main/kotlin/com/chrisjenx/silo/updater/AttestationVerifier.kt`
- Test: exercised via `UpdaterSpec` (Task 11). Add a tiny spec to lock the contract.
- Test: `modules/updater/src/unitTest/kotlin/com/chrisjenx/silo/updater/AttestationVerifierContractSpec.kt`

- [ ] **Step 1: Write `AttestationVerifier.kt`**

```kotlin
package com.chrisjenx.silo.updater

/** Verifies a jar's GitHub build-provenance attestation. Throws [AttestationException] on any failure. */
interface AttestationVerifier {
    /**
     * @param jarSha256Hex sha-256 of the downloaded jar (the attestation subject digest)
     * @param bundleJson raw GitHub attestations API response
     * @param expectedRepo "owner/name" whose release workflow must be the signer
     * @param expectedTag release tag, e.g. "v0.2.0"
     */
    fun verify(jarSha256Hex: String, bundleJson: String, expectedRepo: String, expectedTag: String)
}

class AttestationException(message: String, cause: Throwable? = null) : Exception(message, cause)
```

- [ ] **Step 2: Write `AttestationVerifierContractSpec.kt`** (a hand fake used by later tests; verify it compiles + behaves)

```kotlin
package com.chrisjenx.silo.updater

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec

/** Test double reused by UpdaterSpec. */
class FakeAttestationVerifier(private val fail: Boolean = false) : AttestationVerifier {
    var calls = 0
    override fun verify(jarSha256Hex: String, bundleJson: String, expectedRepo: String, expectedTag: String) {
        calls++
        if (fail) throw AttestationException("forced failure")
    }
}

class AttestationVerifierContractSpec : StringSpec({
    "a failing verifier throws AttestationException" {
        shouldThrow<AttestationException> {
            FakeAttestationVerifier(fail = true).verify("d", "{}", "acme/silo", "v0.2.0")
        }
    }
})
```

- [ ] **Step 3: Run, expect PASS** — `./gradlew :updater:unitTest --tests "*AttestationVerifierContractSpec"`

- [ ] **Step 4: Commit**
```bash
./gradlew :updater:spotlessApply
git add modules/updater
git commit -m "feat(updater): AttestationVerifier interface + test fake"
```

---

## Task 10: `SigstoreAttestationVerifier` (in-JVM, integration-tested)

This is the one task whose exact API surface depends on the resolved `sigstore-java` version. Implement against the `KeylessVerifier` + `Bundle` API, **then compile and adjust signatures to match the resolved version** (Step 4). The interface (Task 9) insulates all callers from these details.

**Files:**
- Create: `modules/updater/src/main/kotlin/com/chrisjenx/silo/updater/SigstoreAttestationVerifier.kt`
- Test: `modules/updater/src/integrationTest/kotlin/com/chrisjenx/silo/updater/SigstoreAttestationVerifierSpec.kt`

- [ ] **Step 1: Capture a real attestation fixture** (one-time, networked — done by the implementer, not committed as code)

```bash
# Get the sha256 of the published v0.1.3 jar and fetch its attestation bundle.
gh release download v0.1.3 --repo chrisjenx/silo --pattern silo.jar --dir /tmp/silofix
DIGEST=$(shasum -a 256 /tmp/silofix/silo.jar | awk '{print $1}')
gh api "/repos/chrisjenx/silo/attestations/sha256:$DIGEST" > modules/updater/src/integrationTest/resources/attestation-v0.1.3.json
echo "$DIGEST" > modules/updater/src/integrationTest/resources/attestation-v0.1.3.sha256
```
Place the two files under `modules/updater/src/integrationTest/resources/`. (Create the dir.) These are the golden inputs for the integration test.

- [ ] **Step 2: Write `SigstoreAttestationVerifierSpec.kt`** (integration test, hits the bundled fixture; the verifier itself may reach Sigstore TUF/Rekor over the network — that's expected for an integration test)

```kotlin
package com.chrisjenx.silo.updater

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec

class SigstoreAttestationVerifierSpec : StringSpec({

    val bundle = SigstoreAttestationVerifierSpec::class.java
        .getResource("/attestation-v0.1.3.json")!!.readText()
    val digest = SigstoreAttestationVerifierSpec::class.java
        .getResource("/attestation-v0.1.3.sha256")!!.readText().trim()

    "verifies the real provenance bundle for the expected repo + tag" {
        SigstoreAttestationVerifier().verify(digest, bundle, "chrisjenx/silo", "v0.1.3")
        // no exception == pass
    }

    "rejects a mismatched subject digest" {
        shouldThrow<AttestationException> {
            SigstoreAttestationVerifier().verify("00".repeat(32), bundle, "chrisjenx/silo", "v0.1.3")
        }
    }

    "rejects an unexpected signer repo" {
        shouldThrow<AttestationException> {
            SigstoreAttestationVerifier().verify(digest, bundle, "evil/fork", "v0.1.3")
        }
    }
})
```

- [ ] **Step 3: Implement `SigstoreAttestationVerifier.kt`**

The GitHub attestations response is `{"attestations":[{"bundle":{...sigstore bundle...}}]}`. Extract each `bundle`, load it, and verify with a certificate-identity policy bound to the repo's release workflow.

```kotlin
package com.chrisjenx.silo.updater

import dev.sigstore.KeylessVerifier
import dev.sigstore.VerificationOptions
import dev.sigstore.VerificationOptions.CertificateMatcher
import dev.sigstore.bundle.Bundle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class SigstoreAttestationVerifier : AttestationVerifier {

    private val json = Json { ignoreUnknownKeys = true }

    override fun verify(jarSha256Hex: String, bundleJson: String, expectedRepo: String, expectedTag: String) {
        val bundles = extractBundles(bundleJson)
        if (bundles.isEmpty()) throw AttestationException("No attestation bundles returned for sha256:$jarSha256Hex")

        // Expected signer identity: the repo's release workflow at the release tag, issued by GitHub Actions OIDC.
        val expectedSan =
            "https://github.com/$expectedRepo/.github/workflows/release.yml@refs/tags/$expectedTag"
        val options = VerificationOptions.builder()
            .addCertificateMatchers(
                CertificateMatcher.fromString(expectedSan, "https://token.actions.githubusercontent.com"),
            )
            .build()

        val verifier = KeylessVerifier.builder().sigstorePublicDefaults().build()
        val digestBytes = jarSha256Hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        var lastError: Exception? = null
        for (raw in bundles) {
            try {
                val bundle = Bundle.from(raw.reader())
                verifier.verify(digestBytes, bundle, options)
                return // first bundle that verifies wins
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw AttestationException(
            "Provenance verification failed for sha256:$jarSha256Hex (expected signer $expectedSan).",
            lastError,
        )
    }

    private fun extractBundles(responseJson: String): List<String> =
        runCatching {
            val root = json.parseToJsonElement(responseJson).jsonObject
            (root["attestations"]?.jsonArray ?: return emptyList())
                .map { (it.jsonObject["bundle"] as JsonObject).toString() }
        }.getOrElse { throw AttestationException("Malformed attestations response", it) }
}
```

> **`CertificateMatcher`/`VerificationOptions`/`Bundle.from` API shape varies by sigstore-java version.** After writing this, run the build (Step 4); if the symbols differ, open the resolved `sigstore-java` Javadoc/sources (`./gradlew :updater:dependencies`) and adjust *only this file*. The required semantics are fixed: (a) subject digest == `jarSha256Hex`, (b) signer SAN == `expectedSan`, (c) issuer == GitHub Actions OIDC, (d) chain to the public-good Fulcio root. If `Bundle.from(Reader)` is unavailable, use the version's documented loader (e.g. `Bundle.from(json)` / a `BundleFactory`).

- [ ] **Step 4: Compile, then run the integration test**

Run: `./gradlew :updater:compileIntegrationTestKotlin` — fix any sigstore-java API mismatches in `SigstoreAttestationVerifier.kt` only.
Run: `./gradlew :updater:integrationTest --tests "*SigstoreAttestationVerifierSpec"`
Expected: PASS (requires network for Sigstore trust material). If CI must run offline, tag this spec so it's excluded from the offline lane and run it in a networked job.

- [ ] **Step 5: Commit**
```bash
./gradlew :updater:spotlessApply
git add modules/updater
git commit -m "feat(updater): in-JVM provenance attestation verification via sigstore-java"
```

---

## Task 11: `Updater` orchestrator

**Files:**
- Create: `modules/updater/src/main/kotlin/com/chrisjenx/silo/updater/Updater.kt`
- Test: `modules/updater/src/unitTest/kotlin/com/chrisjenx/silo/updater/UpdaterSpec.kt`

- [ ] **Step 1: Write `UpdaterSpec.kt`** (drives the whole flow with fakes; a temp dir stands in for the install)

```kotlin
package com.chrisjenx.silo.updater

import io.kotest.core.spec.style.StringSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

private fun sha256Hex(bytes: ByteArray) =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Fake serving a single release whose silo.jar is [newJarBytes]. */
private class FakeReleaseClient(
    private val tag: String,
    private val prerelease: Boolean,
    private val newJarBytes: ByteArray,
) : ReleaseClient {
    override fun latest(includePrerelease: Boolean) =
        Release(tag, SemVer.parse(tag), prerelease, listOf(
            ReleaseAsset("silo.jar", "mem://silo.jar"),
            ReleaseAsset("checksums.txt", "mem://checksums.txt"),
        ))
    override fun byTag(tag: String) = latest(true)
    override fun fetchText(url: String) = "${sha256Hex(newJarBytes)}  silo.jar\n"
    override fun download(url: String, dest: Path) { Files.write(dest, newJarBytes) }
    override fun attestationBundle(sha256Hex: String) = """{"attestations":[{"bundle":{}}]}"""
}

class UpdaterSpec : StringSpec({

    fun install(version: String): Path {
        val dir = tempdir().toPath()
        return dir.resolve("silo.jar").also { Files.writeString(it, "OLD-$version") }
    }

    fun updater(client: ReleaseClient, attestOk: Boolean = true) =
        Updater(
            releaseClient = client,
            attestationVerifier = FakeAttestationVerifier(fail = !attestOk),
            replacer = AtomicJarReplacer(fsync = false),
            repo = "acme/silo",
        )

    "reports UpToDate when the latest equals the current version" {
        val jar = install("0.2.0")
        val client = FakeReleaseClient("v0.2.0", false, "NEW".toByteArray())
        val outcome = updater(client).run(UpdateRequest(currentVersion = "0.2.0"), jarOverride = jar)
        outcome.shouldBeInstanceOf<UpdateOutcome.UpToDate>()
        Files.readString(jar) shouldBe "OLD-0.2.0"
    }

    "check-only reports UpdateAvailable without writing" {
        val jar = install("0.1.3")
        val client = FakeReleaseClient("v0.2.0", false, "NEW".toByteArray())
        val outcome = updater(client).run(UpdateRequest("0.1.3", checkOnly = true), jarOverride = jar)
        outcome.shouldBeInstanceOf<UpdateOutcome.UpdateAvailable>()
        Files.readString(jar) shouldBe "OLD-0.1.3"
    }

    "happy path downloads, verifies, swaps, and reports Updated" {
        val jar = install("0.1.3")
        val newBytes = "NEW-JAR".toByteArray()
        val outcome = updater(FakeReleaseClient("v0.2.0", false, newBytes)).run(
            UpdateRequest("0.1.3"), jarOverride = jar,
        )
        outcome.shouldBeInstanceOf<UpdateOutcome.Updated>()
        Files.readAllBytes(jar) shouldBe newBytes
        Files.exists(jar.resolveSibling("silo.jar.bak")) shouldBe true
    }

    "aborts without swapping when the checksum does not match" {
        val jar = install("0.1.3")
        val lying = object : ReleaseClient by FakeReleaseClient("v0.2.0", false, "NEW".toByteArray()) {
            override fun fetchText(url: String) = "deadbeef  silo.jar\n" // wrong digest
        }
        val outcome = updater(lying).run(UpdateRequest("0.1.3"), jarOverride = jar)
        outcome.shouldBeInstanceOf<UpdateOutcome.Failed>()
        Files.readString(jar) shouldBe "OLD-0.1.3"
    }

    "aborts without swapping when attestation verification fails" {
        val jar = install("0.1.3")
        val outcome = updater(FakeReleaseClient("v0.2.0", false, "NEW".toByteArray()), attestOk = false)
            .run(UpdateRequest("0.1.3"), jarOverride = jar)
        outcome.shouldBeInstanceOf<UpdateOutcome.Failed>()
        Files.readString(jar) shouldBe "OLD-0.1.3"
    }

    "skips attestation when verifyAttestation = false but still enforces checksum" {
        val jar = install("0.1.3")
        val outcome = updater(FakeReleaseClient("v0.2.0", false, "NEW".toByteArray()), attestOk = false)
            .run(UpdateRequest("0.1.3", verifyAttestation = false), jarOverride = jar)
        outcome.shouldBeInstanceOf<UpdateOutcome.Updated>()
    }

    "rollback restores the previous jar" {
        val jar = install("0.1.3")
        updater(FakeReleaseClient("v0.2.0", false, "NEW".toByteArray())).run(UpdateRequest("0.1.3"), jarOverride = jar)
        val outcome = updater(FakeReleaseClient("v0.2.0", false, "NEW".toByteArray()))
            .run(UpdateRequest("0.2.0", rollback = true), jarOverride = jar)
        outcome.shouldBeInstanceOf<UpdateOutcome.RolledBack>()
        Files.readString(jar) shouldBe "OLD-0.1.3"
    }
})
```

- [ ] **Step 2: Run, expect FAIL** — `./gradlew :updater:unitTest --tests "*UpdaterSpec"`

- [ ] **Step 3: Implement `Updater.kt`**

The production entry uses `JarLocator`; tests inject `jarOverride` to skip the code-source lookup.

```kotlin
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

    fun run(request: UpdateRequest, jarOverride: Path? = null): UpdateOutcome {
        val current = runCatching { SemVer.parse(request.currentVersion) }.getOrNull()

        // Resolve the jar (unless a test injects one).
        val jar: Path = when (val located = jarOverride ?: locate(request.currentVersion)) {
            is Path -> located
            is JarLocated -> located.path
            is UpdateOutcome.Failed -> return located
            else -> return UpdateOutcome.Failed("Could not resolve the jar to update.")
        }

        if (request.rollback) {
            return if (replacer.rollback(jar)) UpdateOutcome.RolledBack(jar)
            else UpdateOutcome.Failed("No backup (${jar.fileName}.bak) found to roll back to.")
        }

        if (current == null) return UpdateOutcome.Failed("Current version '${request.currentVersion}' is not semver.")

        val target = runCatching {
            if (request.toTag != null) releaseClient.byTag(request.toTag)
            else releaseClient.latest(request.includePrerelease)
        }.getOrElse { return UpdateOutcome.Failed("Could not fetch release info: ${it.message}") }

        // Up-to-date check (a pinned --to always proceeds, allowing re-install/downgrade).
        if (request.toTag == null && target.version <= current) {
            return if (request.checkOnly) UpdateOutcome.UpToDate(current) else UpdateOutcome.UpToDate(current)
        }
        if (request.checkOnly) return UpdateOutcome.UpdateAvailable(current, target.version)

        val jarAsset = target.asset("silo.jar")
            ?: return UpdateOutcome.Failed("Release ${target.tag} has no silo.jar asset.")
        val checksumsAsset = target.asset("checksums.txt")
            ?: return UpdateOutcome.Failed("Release ${target.tag} has no checksums.txt asset.")

        val tmp = jar.resolveSibling(".silo-update-${UUID.randomUUID()}.tmp")
        try {
            releaseClient.download(jarAsset.downloadUrl, tmp)
            val checksums = releaseClient.fetchText(checksumsAsset.downloadUrl)
            if (!ChecksumVerifier.matches(tmp, "silo.jar", checksums)) {
                return UpdateOutcome.Failed("SHA-256 mismatch for ${target.tag} — refusing to install.")
            }
            if (request.verifyAttestation) {
                val digest = ChecksumVerifier.sha256(tmp)
                val bundle = runCatching { releaseClient.attestationBundle(digest) }
                    .getOrElse { return UpdateOutcome.Failed("Could not fetch attestation: ${it.message}") }
                runCatching {
                    attestationVerifier.verify(digest, bundle, repo, target.tag)
                }.getOrElse { return UpdateOutcome.Failed("Provenance check failed: ${it.message}") }
            }
            replacer.replace(jar = jar, verifiedSource = tmp)
            return UpdateOutcome.Updated(from = current, to = target.version, installedAt = jar)
        } catch (e: Exception) {
            return UpdateOutcome.Failed("Update failed: ${e.message}")
        } finally {
            runCatching { Files.deleteIfExists(tmp) }
        }
    }

    private fun locate(currentVersion: String): Any = JarLocator.locate(currentVersion)
}
```

- [ ] **Step 4: Run, expect PASS** — `./gradlew :updater:unitTest --tests "*UpdaterSpec"`

- [ ] **Step 5: Run the whole module gate** — `./gradlew :updater:check` (unit + integration + kover 80% + lint). Add tests if kover fails the gate.

- [ ] **Step 6: Commit**
```bash
./gradlew :updater:spotlessApply
git add modules/updater
git commit -m "feat(updater): Updater orchestrator wiring fetch/verify/swap"
```

---

## Task 12: `UpdateCommand` (CLI glue in `:server`)

**Files:**
- Modify: `modules/server/build.gradle.kts` (add `implementation(project(":updater"))`)
- Create: `modules/server/src/main/kotlin/com/chrisjenx/silo/server/UpdateCommand.kt`
- Test: `modules/server/src/unitTest/kotlin/com/chrisjenx/silo/server/UpdateCommandSpec.kt`

- [ ] **Step 1: Add the module dependency**

In `modules/server/build.gradle.kts`, add to the `dependencies { ... }` block, with the other `project(...)` lines:
```kotlin
    implementation(project(":updater"))
```

- [ ] **Step 2: Write `UpdateCommandSpec.kt`** (tests flag parsing → `UpdateRequest` and outcome → exit code; the `Updater` is faked through a functional seam)

```kotlin
package com.chrisjenx.silo.server

import com.chrisjenx.silo.updater.SemVer
import com.chrisjenx.silo.updater.UpdateOutcome
import com.chrisjenx.silo.updater.UpdateRequest
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path

class UpdateCommandSpec : StringSpec({

    fun runWith(args: List<String>, outcome: UpdateOutcome): Pair<Int, UpdateRequest> {
        var captured: UpdateRequest? = null
        val code = UpdateCommand.run(
            args = args,
            currentVersion = "0.1.3",
            repoDefault = "chrisjenx/silo",
            envRepo = null,
            envToken = null,
            confirm = { true },
        ) { request -> captured = request; outcome }
        return code to captured!!
    }

    "--check available maps to exit code 10" {
        val (code, req) = runWith(listOf("--check"), UpdateOutcome.UpdateAvailable(SemVer(0, 1, 3), SemVer(0, 2, 0)))
        code shouldBe 10
        req.checkOnly shouldBe true
    }

    "up to date maps to exit code 0" {
        val (code, _) = runWith(listOf("--check"), UpdateOutcome.UpToDate(SemVer(0, 1, 3)))
        code shouldBe 0
    }

    "updated maps to exit code 0" {
        val (code, _) = runWith(emptyList(), UpdateOutcome.Updated(SemVer(0, 1, 3), SemVer(0, 2, 0), Path.of("/x/silo.jar")))
        code shouldBe 0
    }

    "failure maps to exit code 1" {
        val (code, _) = runWith(emptyList(), UpdateOutcome.Failed("boom"))
        code shouldBe 1
    }

    "flags populate the request" {
        val (_, req) = runWith(
            listOf("--to", "v0.3.0", "--yes", "--prerelease", "--no-verify-attestation"),
            UpdateOutcome.Updated(SemVer(0, 1, 3), SemVer(0, 3, 0), Path.of("/x/silo.jar")),
        )
        req.toTag shouldBe "v0.3.0"
        req.includePrerelease shouldBe true
        req.verifyAttestation shouldBe false
    }
})
```

- [ ] **Step 3: Run, expect FAIL** — `./gradlew :server:unitTest --tests "*UpdateCommandSpec"`

- [ ] **Step 4: Implement `UpdateCommand.kt`**

`run` is written for testability: it takes the current version, env values, a `confirm` lambda, and an `execute` lambda (defaulting to a real `Updater`). The default `execute` builds the real wiring.

```kotlin
package com.chrisjenx.silo.server

import com.chrisjenx.silo.updater.AtomicJarReplacer
import com.chrisjenx.silo.updater.GitHubReleaseClient
import com.chrisjenx.silo.updater.SigstoreAttestationVerifier
import com.chrisjenx.silo.updater.UpdateOutcome
import com.chrisjenx.silo.updater.UpdateRequest
import com.chrisjenx.silo.updater.Updater

object UpdateCommand {

    private const val EXIT_OK = 0
    private const val EXIT_ERROR = 1
    private const val EXIT_UPDATE_AVAILABLE = 10

    /** Real entry from Main: reads SiloVersion + env, prompts on a TTY, runs the real Updater. */
    fun run(args: List<String>): Int =
        run(
            args = args,
            currentVersion = SiloVersion.version,
            repoDefault = "chrisjenx/silo",
            envRepo = System.getenv("SILO_UPDATE_REPO"),
            envToken = System.getenv("SILO_UPDATE_TOKEN"),
            confirm = ::promptYesNo,
        )

    /** Testable core. [execute] defaults to the real Updater built from parsed options. */
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
        val request = UpdateRequest(
            currentVersion = currentVersion,
            toTag = optionValue(args, "--to"),
            checkOnly = args.contains("--check"),
            includePrerelease = args.contains("--prerelease"),
            verifyAttestation = !args.contains("--no-verify-attestation"),
            rollback = args.contains("--rollback"),
        )

        // Confirm destructive replace unless --check/--rollback/--yes.
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

    private fun defaultUpdater(repo: String, token: String?): Updater =
        Updater(
            releaseClient = GitHubReleaseClient(repo = repo, token = token),
            attestationVerifier = SigstoreAttestationVerifier(),
            replacer = AtomicJarReplacer(),
            repo = repo,
        )

    private fun report(outcome: UpdateOutcome): Int =
        when (outcome) {
            is UpdateOutcome.UpToDate -> {
                println("silo ${outcome.current} is up to date."); EXIT_OK
            }
            is UpdateOutcome.UpdateAvailable -> {
                println("A newer version is available: ${outcome.current} -> ${outcome.latest}. Run 'silo update' to install.")
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
                System.err.println("Update failed: ${outcome.reason}"); EXIT_ERROR
            }
        }

    private fun optionValue(args: List<String>, flag: String): String? {
        val i = args.indexOf(flag)
        return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
    }

    private fun promptYesNo(message: String): Boolean {
        print(message)
        val line = readlnOrNull()?.trim()?.lowercase() ?: return false
        return line == "y" || line == "yes"
    }
}
```

- [ ] **Step 5: Run, expect PASS** — `./gradlew :server:unitTest --tests "*UpdateCommandSpec"`

- [ ] **Step 6: Commit**
```bash
./gradlew :server:spotlessApply
git add modules/server
git commit -m "feat(server): UpdateCommand mapping flags + outcomes to exit codes"
```

---

## Task 13: Dispatch `update` from `Main.kt`

**Files:**
- Modify: `modules/server/src/main/kotlin/com/chrisjenx/silo/server/Main.kt`

- [ ] **Step 1: Add the dispatch branch**

In `Main.kt`, after the `hash-password` branch and before the config/server start, add:
```kotlin
    if (args.firstOrNull() == "update") {
        exitProcess(UpdateCommand.run(args.drop(1)))
    }
```
And update the KDoc above `main` to document the verb:
```kotlin
 * `update` checks GitHub Releases and self-replaces the jar after verifying it. See `silo update --help`.
```

- [ ] **Step 2: Add a `--help`/usage line for `update`** (minimal, no new dep)

In `UpdateCommand.run(args, ...)` (the real overload), add at the top:
```kotlin
        if (args.contains("--help") || args.contains("-h")) {
            println(USAGE)
            return EXIT_OK
        }
```
and add the constant to `UpdateCommand`:
```kotlin
    private val USAGE = """
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
```

- [ ] **Step 3: Build the fat jar and smoke-test `--check` end to end**

Run: `./gradlew :server:shadowJar`
Run: `java -jar modules/server/build/libs/silo-*-all.jar update --check`
Expected: prints either "up to date" (exit 0) or "A newer version is available …" (exit 10) against the real GitHub repo. (This is a real network call to `api.github.com`; if rate-limited, set `SILO_UPDATE_TOKEN`.)

> Note: when run from the shadow jar, `SiloVersion.version` is the built version (e.g. a `-SNAPSHOT` or the `silo.version` property). If it's `dev`, `--check` will still query but a real `update` will refuse with the dev-build guard — that's expected.

- [ ] **Step 4: Commit**
```bash
./gradlew :server:spotlessApply
git add modules/server
git commit -m "feat(server): dispatch 'update' subcommand from Main"
```

---

## Task 14: Documentation

**Files:**
- Modify: `docs/operations.md` (new "Updating" section)
- Modify: `docs/configuration.md` (env-var quick reference + command list)
- Modify: `README.md` (Quick start note)

- [ ] **Step 1: Add an "Updating" section to `docs/operations.md`** (insert after "First boot")

```markdown
## Updating

**Fat-jar / CLI installs** have a built-in updater:

```bash
java -jar silo.jar update --check     # is a newer version available? (exit 10 = yes)
java -jar silo.jar update             # download, verify, install, then restart silo
java -jar silo.jar update --to v0.2.0 # pin a specific version
java -jar silo.jar update --rollback  # restore the previous jar (silo.jar.bak)
```

The updater downloads the release jar, verifies its **SHA-256** against the release
`checksums.txt` **and** its **GitHub build-provenance attestation** before atomically
replacing the on-disk jar. It never restarts the process — restart silo yourself
(`systemctl restart silo`, or re-run `java -jar silo.jar`). Take a backup before major upgrades
(see [Backup](#backup)).

**Docker / container installs** do not self-update; pull a new image tag instead
(`docker pull ghcr.io/chrisjenx/silo:<version>`). The updater detects a non-writable jar and
tells you so.

Set `SILO_UPDATE_TOKEN` to a GitHub token if you hit API rate limits, and `SILO_UPDATE_REPO`
to update from a fork.
```

- [ ] **Step 2: Add env vars to `docs/configuration.md`** (append to the env-var quick-reference table)

```markdown
| `SILO_UPDATE_REPO` | _(updater)_ `silo update` source repo | `chrisjenx/silo` |
| `SILO_UPDATE_TOKEN` | _(updater)_ GitHub token for rate limits / private forks | _(unset)_ |
```
And near the `hash-password` example block, note the `update` verb exists.

- [ ] **Step 3: Add a README Quick start note** under the "Fat jar" subsection

```markdown
Upgrade in place with `java -jar silo.jar update` (verifies checksum + provenance before swapping).
```

- [ ] **Step 4: Commit**
```bash
git add docs/operations.md docs/configuration.md README.md
git commit -m "docs: document the silo update command"
```

---

## Task 15: Full build + final verification

- [ ] **Step 1: Run the whole project gate**

Run: `./gradlew clean check`
Expected: PASS — all module `unitTest`/`integrationTest`, `koverVerify` (incl. `:updater` ≥80% line), ktlint, detekt, spotless. Fix any coverage gaps by adding targeted unit tests (most likely in `Updater`/`UpdateCommand` branches).

- [ ] **Step 2: Confirm no forbidden dependency crept in**

Run: `./gradlew :server:dependencies --configuration runtimeClasspath | grep -Ei "jackson|spring|hibernate|exposed|koin"`
Expected: no output. (sigstore-java must not drag in Jackson — see Task 1 Step 6.)

- [ ] **Step 3: Re-run the end-to-end smoke from the fat jar**

Run: `./gradlew :server:shadowJar && java -jar modules/server/build/libs/silo-*-all.jar update --check`
Expected: a sensible up-to-date / update-available message and matching exit code.

- [ ] **Step 4: Final commit (if anything changed) + open PR**

```bash
git add -A
git commit -m "test: tidy coverage for :updater" || true
```
Open a PR titled `feat: built-in 'silo update' self-update command (#144)` summarizing the design (link the spec), the verification model (SHA-256 + provenance), and the Docker-out-of-scope behavior.

---

## Self-Review

**1. Spec coverage**

| Spec requirement | Task(s) |
|---|---|
| On-demand `silo update` command | 12, 13 |
| `--check` dry run (exit 10) | 11, 12 |
| `--to`, `--yes`, `--prerelease`, `--no-verify-attestation`, `--rollback`, `--repo` | 11, 12 |
| GitHub Releases channel (`/latest`, `/tags`) | 8 |
| SHA-256 hard gate vs `checksums.txt` | 3, 11 |
| In-JVM provenance attestation verification | 9, 10, 11 |
| Reuse existing attestation (no pipeline change) | 10 (uses attestations API) |
| JDK `HttpClient` + `kotlinx-serialization` | 7, 8, Task 1 build |
| `JarLocator` dev/exploded/not-writable (Docker) guards | 5 |
| Atomic swap + `.bak` + Windows fallback + fsync | 6 |
| `:updater` module + `:server` wiring + `Main.kt` dispatch | 1, 12, 13 |
| Env vars `SILO_UPDATE_REPO` / `SILO_UPDATE_TOKEN` | 12, 14 |
| Docs (operations/configuration/README) | 14 |
| TDD, kotest, unit/integration split, 80% kover | all tasks; gate in 11, 15 |
| Sigstore-java forbidden-dep risk check | 1 (Step 6), 15 (Step 2) |

No uncovered requirements.

**2. Placeholder scan** — no TBD/TODO; every code step has complete code. The one version-dependent spot (sigstore-java API in Task 10) ships concrete code plus an explicit "compile-and-adjust this file only" instruction with the fixed semantics it must satisfy — not a placeholder.

**3. Type consistency** — `ReleaseClient` (5 methods), `Release`/`ReleaseAsset`, `SemVer`, `UpdateOutcome` variants (`UpToDate`/`UpdateAvailable`/`Updated`/`RolledBack`/`Failed`), `UpdateRequest` fields, `AttestationVerifier.verify(jarSha256Hex, bundleJson, expectedRepo, expectedTag)`, `AtomicJarReplacer.replace(jar, verifiedSource)`/`rollback(jar)`, `JarLocator.locate(currentVersion, codeSource)` → `JarLocated`/`Failed`, and `Updater.run(request, jarOverride)` are used identically across the tasks that produce and consume them. `UpdateCommand.run(...)` signature matches its spec test.
