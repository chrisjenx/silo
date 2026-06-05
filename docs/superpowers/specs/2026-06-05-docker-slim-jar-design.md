# Design — full vs slim jar (drop the self-updater from the Docker image)

- **Parent work:** builds on #144 / PR #145 (the `silo update` self-update command).
- **Status:** Approved design, ready for implementation plan
- **Date:** 2026-06-05
- **Author:** Silo contributors

## Summary

Produce **two** fat jars from the build:

- **Full jar** (`silo-<version>-all.jar`) — server + the `silo update` self-update plugin (`:updater` + sigstore-java). This is the GitHub Release asset that standalone/CLI users download; self-update works.
- **Slim jar** (`silo-<version>-slim.jar`) — server only, **no** `:updater`/sigstore. This is what the Docker image ships. ~32 MB smaller and a smaller dependency/CVE surface, because a container can never self-update anyway (it pulls a new image).

The two are decoupled by making the `update` verb a `Subcommand` that is **wired explicitly per entry point at compile time** — no `ServiceLoader`, no reflection, no DI framework.

## Goals

- Docker image excludes `:updater` + sigstore-java (gRPC/protobuf/BouncyCastle) → smaller image and SBOM/CVE surface.
- Standalone download keeps full self-update (no regression to #144).
- `silo update` in the slim build prints a clear redirect and exits non-zero.
- Compile-time-safe subcommand wiring (the concern that ruled out `ServiceLoader`).
- No new framework dependency; no Kotlin version bump.

## Non-goals

- **No DI framework.** Metro/Koin/etc. were considered and rejected for this feature: the artifact split is a Gradle/classpath concern (a framework wouldn't help it), explicit wiring already gives compile-time safety for ~3 verbs, and adopting a compiler-plugin DI framework would force a Kotlin 2.2.0→2.3.0+ bump and reverse the project's deliberate "no DI in core" stance. (If DI is later adopted codebase-wide, contributing subcommands via aggregation can be revisited then — separate initiative.)
- **No change to update behavior.** The verification/swap/rollback flow from #144 is unchanged; it just moves behind a `Subcommand`.
- **No slim standalone release asset.** Slim is build-internal (Docker only); only the full jar is published, so signing/SBOM/attestation surface is unchanged (one public jar).

## Background

- Today `:server` hard-depends on `:updater` (`modules/server/build.gradle.kts` `implementation(project(":updater"))`) and `Main.kt` calls `UpdateCommand.run` directly, so the dependency can't simply be dropped from one jar.
- The full fat jar is ~72 MB; without `:updater`/sigstore it returns to ~40 MB.
- The Dockerfile builds `:server:shadowJar` and copies `silo-*-all.jar` into the image; `release.yml` builds `:server:shadowJar` for the `silo.jar` release asset.
- `JarLocator` already refuses to self-update a non-writable jar (the Docker `/app/silo.jar` case), so the updater code is 100% dead weight inside the image.

## Key decisions

| Decision | Choice |
|---|---|
| Subcommand wiring | Explicit per-entry-point (`runCli(args, extra)`), compile-time |
| Release artifact | Full jar only (`silo-*-all.jar`); slim is Docker-internal |
| Slim `silo update` | Friendly redirect to `docker pull …`, exit non-zero |
| New module | `:server-update` (produces the full jar) |
| `:server` jar | Becomes the **slim** jar |

## Architecture

### Module graph (cycle-free)

```
:server          Ktor app + Main (slim entry) + SiloVersion + `Subcommand` SPI + `runCli`.
                 NO dependency on :updater.
                 :server:shadowJar  → silo-<v>-slim.jar  (Docker)
:updater         unchanged (update logic + sigstore-java)
:server-update   NEW. implementation(:server) + implementation(:updater).
                 UpdateSubcommand : Subcommand  +  Main (full entry, wires UpdateSubcommand).
                 :server-update:shadowJar → silo-<v>-all.jar  (release / standalone)
```

`:server-update` → `:server` is the only edge (no cycle). `:updater` stays pure (no `:server` dep).

### The `Subcommand` seam (in `:server`)

```kotlin
interface Subcommand {
    val name: String
    fun run(args: List<String>): Int
}
```

A shared dispatcher in `:server`, used by both entry points:

```kotlin
// Returns the process exit code, or null to mean "fall through and start the server".
fun runCli(args: Array<String>, extra: List<Subcommand>): Int? {
    if (args.any { it == "--version" || it == "-V" }) { println(SiloVersion.line()); return 0 }
    if (args.firstOrNull() == "hash-password") return runHashPassword()
    val verb = args.firstOrNull()
    extra.firstOrNull { it.name == verb }?.let { return it.run(args.drop(1)) }
    if (verb in RESERVED_PLUGIN_VERBS) {           // e.g. "update" present in the full jar only
        System.err.println(notBundledMessage(verb))
        return 1
    }
    return null                                     // no subcommand → start server
}
```

`RESERVED_PLUGIN_VERBS = setOf("update")` and `notBundledMessage("update")` prints:
`self-update isn't bundled in this build — pull a new image: docker pull ghcr.io/chrisjenx/silo:<version>` (and points to `docs/operations.md`).

`--version`/`-V` and `hash-password` stay built-in (no heavy deps); only `update` is a plug-in. Slim `Main` passes `extra = emptyList()`; full `Main` passes `extra = listOf(UpdateSubcommand)`.

### Entry points

```kotlin
// :server  — slim jar Main-Class
fun main(args: Array<String>) {
    runCli(args, extra = emptyList())?.let { exitProcess(it) }
    startServer()
}
// :server-update  — full jar Main-Class
fun main(args: Array<String>) {
    runCli(args, extra = listOf(UpdateSubcommand))?.let { exitProcess(it) }
    startServer()
}
```

`startServer()` (the `embeddedServer(...).start(wait = true)` block) is extracted into `:server` so both entry points share it. `UpdateSubcommand` (in `:server-update`) wraps the existing `UpdateCommand` logic (which moves from `:server` to `:server-update` along with `UpdateCommandSpec`); it reads `SiloVersion` via the `:server` dependency.

### Both shadowJars

Each sets the manifest (`Main-Class`, `Implementation-Title/Version/SHA`) and `mergeServiceFiles()` (for the existing `BackendFactory`/Netty/SLF4J service files). `archivesName = "silo"`; `:server` uses classifier `slim`, `:server-update` keeps `all`. The CycloneDX SBOM for the published asset runs on `:server-update` (the full jar).

## Build / release / CI changes

- **Dockerfile:** `./gradlew :server:shadowJar -x test` then `cp modules/server/build/libs/silo-*-slim.jar /tmp/silo.jar`.
- **release.yml:** build `:server-update:shadowJar` + `:server-update:cyclonedxBom`; `cp modules/server-update/build/libs/silo-*-all.jar dist/silo.jar`. Checksums/attestation/SBOM unchanged in shape (different module path). Docker job is unchanged (it builds slim via the Dockerfile).
- **ci.yml guard:** after assembling the slim jar, assert it contains **no** `com/chrisjenx/silo/updater/` or `dev/sigstore/` or `io/grpc/` entries (`unzip -l … | grep -q` → fail if present), and assert the full jar **does** contain them. Prevents silent regression of the split.
- **CLAUDE.md / docs:** note that `:server-update:shadowJar` is the full/primary distributable and `:server:shadowJar` is the slim Docker jar; update the operations "Updating" section to mention the container redirect message.

## Testing (TDD)

- `:server` unit test: `runCli` dispatches `--version`/`hash-password`, routes a matching `extra` subcommand, prints the redirect + returns `1` for `update` when not in `extra`, and returns `null` (start server) otherwise. Uses a fake `Subcommand`.
- `:server-update` unit test: `UpdateSubcommand.name == "update"` and `run` delegates to the update flow (reuse/relocate `UpdateCommandSpec`).
- Build/CI guard (above) is the integration-level check that the slim jar really excludes the updater.

## Risks & mitigations

- **Manifest config duplicated across two shadowJars.** Acceptable (~15 lines); optionally factor a `silo.fatjar` convention later — not now (YAGNI).
- **`:server:shadowJar` semantics change** (now slim) — anyone scripting it for a full jar must switch to `:server-update:shadowJar`. Mitigated by the CLAUDE.md/doc note and the CI guard.
- **Two entry-point `main`s** could drift. Mitigated by keeping them one-liners over the shared `runCli`/`startServer` in `:server`.

## Delivery

This restructures code introduced in PR #145, which is not yet merged. The branch is based on #145's HEAD and delivered as a **separate PR stacked on #145**; rebased onto `main` once #145 lands.
