# Design — `silo update` self-update command (issue #144)

- **Issue:** [#144 "Auto update"](https://github.com/chrisjenx/silo/issues/144)
- **Status:** Approved design, ready for implementation plan
- **Date:** 2026-06-04
- **Author:** Silo contributors

## Summary

Add a built-in, on-demand `silo update` subcommand so operators running the fat jar
directly (`java -jar silo.jar`) can upgrade in place: it checks GitHub Releases for a
newer version, downloads the new jar, **verifies it (SHA-256 + GitHub build-provenance
attestation)** before touching anything, atomically replaces the on-disk jar, and tells
the operator to restart. No background polling, no telemetry — every network call happens
only when a human runs the command.

## Goals

- One command, `silo update`, that upgrades a directly-run jar safely.
- A dry-run mode (`silo update --check`) suitable for scripts/cron.
- Verification at least as strong as the release pipeline's own guarantees: integrity
  (SHA-256) **and** provenance (the jar was built by Silo's release workflow).
- Self-contained: works on any jar install with no external tools required.
- Preserve the project's "no phone-home / no telemetry" promise.

## Non-goals (out of scope)

- **Background / unattended auto-update.** No timer, no server-initiated downloads. Rejected
  during brainstorming as conflicting with the no-phone-home promise and with how long-lived
  servers are managed (systemd/Docker/k8s own the restart lifecycle).
- **Docker / container in-place update.** The image bakes `/app/silo.jar` into a root-owned
  layer run as the unprivileged `silo` user; self-replace is neither possible nor desirable.
  Containers update by pulling a new image tag. The command detects this case and says so.
- **Auto-restart of a running server.** The command never restarts a process it did not
  start; it prints restart instructions and exits.
- **A new release-pipeline signature.** We reuse the existing build-provenance attestation;
  no `cosign sign-blob` step is added to `release.yml`.

## Background — how Silo ships and what is signed

- Distribution: a **fat jar** `silo.jar` attached to each GitHub Release (alongside
  `silo-sbom.cdx.json` and `checksums.txt`), and a multi-arch Docker image on ghcr.io.
  No native binary — pure JVM 21+.
- The Docker **image** is cosign keyless-signed (signature lives in the registry, signs the
  image digest — irrelevant to a jar update).
- The **jar** is attested with `actions/attest-build-provenance` — a Sigstore/Fulcio-backed
  SLSA provenance statement stored in GitHub's **attestations API**, *not* a detached
  `.sig`/`.pem` asset on the release. It is fetchable at
  `GET /repos/{owner}/{repo}/attestations/sha256:{digest}` and is the trust artifact this
  feature verifies.
- Existing building blocks we reuse:
  - `Main.kt` already dispatches subcommands (`--version`/`-V`, `hash-password`) before
    starting the server — `update` slots in the same way.
  - `SiloVersion.version` (jar manifest `Implementation-Version`) gives the current version,
    and `SiloVersion::class.java.protectionDomain.codeSource.location` already locates the
    running jar — the exact handle a self-replace needs.
  - `kotlinx-serialization-json` 1.8.0 is already in the version catalog (currently unused) —
    used here to parse the GitHub API JSON (Jackson is forbidden).
  - No HTTP client exists; the JDK's built-in `java.net.http.HttpClient` (JVM 21) is used —
    no new runtime dependency for networking.

## Key decisions (from brainstorming)

| Decision | Choice |
|---|---|
| Scope | On-demand `silo update` command only (no background updater) |
| Update channel | GitHub Releases API (`/releases/latest`, `/releases/tags/{tag}`) |
| Integrity check | SHA-256 of downloaded jar vs `checksums.txt` — **mandatory hard gate** |
| Provenance check | GitHub build-provenance attestation, verified **in-JVM** — on by default |
| Trust artifact | Reuse existing attestation (no pipeline change) |
| HTTP client | JDK `java.net.http.HttpClient` |
| JSON | `kotlinx-serialization-json` |
| Restart | Manual — print instructions, never auto-restart |

## Command surface

```
silo update                          # check → download → verify → atomic swap → "restart to apply"
silo update --check                  # dry run: print current vs latest, no download
silo update --to v0.2.0              # pin a specific version (up- or downgrade)
silo update --yes                    # non-interactive (skip the confirm prompt)
silo update --prerelease             # consider prereleases (default: stable only)
silo update --no-verify-attestation  # skip provenance only; SHA-256 still enforced
silo update --rollback               # restore silo.jar.bak from the previous update
silo update --repo owner/name        # fork override (default: chrisjenx/silo)
```

Environment:

| Env var | Effect | Default |
|---|---|---|
| `SILO_UPDATE_REPO` | Source repo (`owner/name`) | `chrisjenx/silo` |
| `SILO_UPDATE_TOKEN` | GitHub token for rate limits / private forks | _(unset → anonymous)_ |

Exit codes:

| Code | Meaning |
|---|---|
| `0` | Success — updated, already up to date, or `--check` reports current |
| `10` | `--check` only: a newer version is available (no change made) |
| `1` | Error — network failure, verification failure, not-a-jar, not writable, etc. |

## Architecture — new `:updater` module

A new pure-Kotlin module `:updater` (package `com.chrisjenx.silo.updater`), **no Ktor**,
mirroring the build conventions of an existing pure-Kotlin module (`:protocol`). Behaviour is
split behind small interfaces so the heavy verifier is isolated and every piece is unit-testable
with fakes:

```
:updater
  ReleaseClient        interface  - GitHub API + asset downloads
    GitHubReleaseClient impl      - JDK HttpClient; latest/by-tag, attestation bundle, stream download
  JarLocator           - resolves the running jar path; guards dev-build / exploded / not-writable
  SemVer               - parse + compare major.minor.patch[-prerelease]
  ChecksumVerifier     - SHA-256 of a file vs a checksums.txt entry
  AttestationVerifier  interface  - verify provenance bundle for a digest
    SigstoreAttestationVerifier   - in-JVM (sigstore-java)
  AtomicJarReplacer    - temp-write handoff → ATOMIC_MOVE (+ fallback) → fsync → .bak
  Updater              - orchestrator; returns sealed UpdateOutcome
```

`UpdateOutcome` is a sealed type: `UpToDate`, `Updated(from, to, path)`,
`UpdateAvailable(current, latest)` (for `--check`), `RolledBack(to)`, `Failed(reason)`.

`:server` gains a dependency on `:updater`. `Main.kt` dispatches the `update` verb to an
`UpdateCommand` (flag parsing + human-readable stdout + `UpdateOutcome`→exit-code mapping),
keeping `:server` otherwise unchanged. The command runs **without starting the server** and
logs to stdout (human-facing), not the JSON server logger.

## Flow

1. **Locate target jar** (`JarLocator`): read `protectionDomain.codeSource.location`.
   - Not a `.jar` file (directory / exploded classpath / `:run`) → `Failed`: "self-update only
     works on a packaged jar."
   - `SiloVersion.version == "dev"` → `Failed`: "running an unversioned/dev build; nothing to
     compare against."
   - Jar or parent dir not writable → `Failed`: "this looks like a managed/container install
     (e.g. Docker `/app/silo.jar`) — update by pulling a new image / via your package manager."
     *(This is how the Docker case is handled cleanly.)*
2. **Resolve target release** (`ReleaseClient`): `GET /repos/{repo}/releases/latest`
   (stable-only by GitHub semantics), or `/releases/tags/{tag}` for `--to`, or the newest of
   `/releases` including prereleases for `--prerelease`. Read `tag_name` and the
   `silo.jar` + `checksums.txt` asset download URLs. Send `Accept: application/vnd.github+json`
   and `Authorization: Bearer <token>` when `SILO_UPDATE_TOKEN` is set. On HTTP 403 + rate-limit
   headers → `Failed` suggesting a token.
3. **Compare versions** (`SemVer`): `tag.removePrefix("v")` vs `SiloVersion.version`.
   For plain `update`, `latest <= current` → `UpToDate` (exit 0). `--to` always proceeds
   (allows downgrade). `--check` stops here → `UpToDate` (0) or `UpdateAvailable` (10).
4. **Confirm** unless `--yes`: print `current → target` and prompt (default no on EOF/non-TTY).
5. **Download** `checksums.txt` (tiny) and `silo.jar` to `<jarDir>/.silo-update-<uuid>.tmp`,
   **streamed to disk** (never buffer ~40 MB in heap — mirrors the project's streaming ethos),
   with a sane max-size cap and progress output. `ENOSPC`/IO error → clean up temp, `Failed`.
6. **Verify — hard gates, original jar untouched until both pass:**
   - **SHA-256** (`ChecksumVerifier`): digest of the temp file == the `silo.jar` line in the
     downloaded `checksums.txt`. Mismatch → delete temp, `Failed`.
   - **Provenance** (`AttestationVerifier`, unless `--no-verify-attestation`): fetch the bundle
     from `GET /repos/{repo}/attestations/sha256:<digest>`, then verify with sigstore-java that
     (a) the certificate chains to the Fulcio root, (b) the OIDC issuer is GitHub Actions and the
     signer SAN identity matches `https://github.com/{repo}/.github/workflows/release.yml@refs/tags/<tag>`,
     and (c) the attested subject digest == the downloaded jar's SHA-256. Any failure → delete
     temp, `Failed`. (Forks that don't produce attestations must pass `--no-verify-attestation`;
     documented.)
7. **Atomic swap** (`AtomicJarReplacer`), mirroring the cache atomic-write protocol:
   - `fileChannel.force(true)` the temp; copy current jar → `silo.jar.bak` (rollback point);
     `Files.move(tmp, jar, ATOMIC_MOVE, REPLACE_EXISTING)`; fsync parent dir.
   - On `AtomicMoveNotSupportedException` / Windows lock: rename current → `.bak`, move temp →
     jar, roll back from `.bak` on any failure. WARN on the non-atomic path.
8. **Report & stop:** print `silo <from> → <to> installed at <path>. Restart to apply
   (e.g. systemctl restart silo, or re-run java -jar silo.jar).` Exit 0. No auto-restart.

`silo update --rollback`: if `silo.jar.bak` exists, atomically restore it over `silo.jar`
(same replacer), print the restored version, exit 0; else `Failed`.

## Verification details

- **SHA-256** is always enforced and is the integrity floor. `checksums.txt` is itself an
  authenticated release asset fetched over TLS from GitHub.
- **Provenance** raises the bar to "this jar was built by Silo's own release workflow," using
  the same Sigstore trust root cosign uses. Verified in-JVM so no `gh`/`cosign` binary is needed.
- The `AttestationVerifier` **interface** is the seam: if `sigstore-java` proves to drag in a
  forbidden dependency or is otherwise unworkable, an alternative impl (e.g. shelling out to
  `gh attestation verify` when present, else SHA-256-only with a loud warning) can be swapped in
  without touching callers. The design target remains the self-contained in-JVM verifier.

## Configuration & docs

- Env vars `SILO_UPDATE_REPO` / `SILO_UPDATE_TOKEN` documented in `docs/configuration.md`
  (env-var quick reference table). No HOCON server config is required for an on-demand command.
- `docs/operations.md`: add an **"Updating"** section — `silo update` for jar installs, image
  pull for Docker, and a note that backups should precede major upgrades (link existing Backup
  section).
- `README.md` Quick start: one line under "Fat jar" mentioning `silo update`.
- `docs/configuration.md` Users-file example block already shows `hash-password`; add the
  `update` verb to any command list.

## Testing strategy (TDD — red → green → refactor)

All in `:updater` with kotest 6, fakes over interfaces; unit + integration split per project
convention. Coverage gate applies (`:updater` is logic-heavy → target the 80% line gate like
`:protocol`/`:storage`).

- `SemVer` — table-driven comparisons incl. prerelease ordering and `v` prefix.
- `ChecksumVerifier` — known SHA-256 vectors; malformed/missing `checksums.txt` lines.
- `JarLocator` — jar vs directory vs non-writable (use a temp dir + permission flips); dev-build guard.
- `AtomicJarReplacer` — temp→final over a tmp dir; `.bak` creation; simulated move failure → rollback;
  fsync path exercised.
- `ReleaseClient` — fake HTTP layer serving canned `releases/latest`, `releases/tags`, attestation,
  and asset bytes; rate-limit (403) handling; `--to`/`--prerelease` selection.
- `AttestationVerifier` — recorded **real** provenance bundle fixtures (valid) + tampered/negative
  cases (wrong subject digest, wrong SAN identity, broken chain) all rejected.
- `Updater` orchestration — up-to-date, update-happy-path, checksum-fail-aborts-without-swap,
  attestation-fail-aborts-without-swap, `--check` exit semantics, rollback.
- `UpdateCommand` — flag parsing and `UpdateOutcome`→exit-code mapping.

## Risks & mitigations

- **`sigstore-java` footprint.** Adds BouncyCastle/protobuf/Gson and grows the ~40 MB jar by a
  few MB. *Mitigation:* confirm at implementation time it pulls no forbidden dep (notably
  Jackson); the `AttestationVerifier` interface allows a lighter fallback if needed.
- **GitHub API rate limits** (60/hr anonymous). *Mitigation:* `SILO_UPDATE_TOKEN`; the command is
  infrequent and human-initiated.
- **Windows file locking** on the running jar. *Mitigation:* the `.bak` rename + rollback fallback
  path, with a WARN; documented.
- **Fork installs** lack the attestation identity. *Mitigation:* `--repo` sets the expected
  identity; forks without attestations use `--no-verify-attestation` (documented).

## Future (explicitly deferred)

- Opt-in "newer version available" notice in logs/admin UI (the brainstorm's option 2).
- `cosign verify-blob`/`gh`-based external verifier impl, if the in-JVM path is dropped.
- Package-manager distribution (Homebrew/scoop/apt) as an alternative update path for managed installs.
