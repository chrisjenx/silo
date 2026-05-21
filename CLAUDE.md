# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

**Silo** — OSS Kotlin/Ktor replacement for the EOL Gradle Remote Build Cache Node. Drop-in HTTP
server speaking the Gradle build cache protocol. Fat-jar and multi-arch Docker. Apache-2.0.

Status: pre-scaffold. The repo currently holds docs and CI/Docker scaffolding; the Gradle build
and Kotlin source are filed as GitHub issues #1–#5 and will land via PRs.

Approved bootstrap plan: `~/.claude-work/plans/project-goal-and-archetecture-mossy-naur.md`.

## Stack

- Kotlin 2.x latest stable, JVM 21+ (virtual threads)
- Ktor 3.5, Netty engine (high-throughput streaming binary)
- Gradle with Kotlin DSL, **multi-module** layout
- SQLite WAL (`org.xerial:sqlite-jdbc`) for the metadata index
- Micrometer + Prometheus for metrics
- Logback + logstash-encoder (JSON logs)
- kotest 6 for tests (`testApplication { }` for HTTP)
- Kobweb (Compose-HTML, static export) for the admin SPA
- Shadow plugin for the fat jar; `docker buildx` for multi-arch images

**Forbidden**: Spring, Koin in core, Jackson, ORMs (Hibernate/Exposed for cache data — use plain JDBC against SQLite), `!!` (detekt blocks it), `runBlocking` outside `main` and tests.

## Gradle Build Cache Protocol

Server MUST implement the [Gradle HTTP build cache protocol](https://docs.gradle.org/current/userguide/build_cache.html#sec:build_cache_configure_remote):

- `GET /cache/{key}` → `2xx` + body bytes | `404` cache miss (NOT an error from the client's perspective)
- `PUT /cache/{key}` → any `2xx` = stored. `413` = too large (non-fatal to the Gradle client)
- `HEAD /cache/{key}` → existence check, no body
- Optional HTTP Basic auth. Anonymous-read toggle for legacy node parity.
- Honor `Expect: 100-continue` — reject (`413`/`401`) before consuming the body.
- Body is opaque binary. Stream it; never buffer.

Validate keys before touching the filesystem (regex `^[a-f0-9]{8,128}$` — opaque hex, tolerant of future Gradle hash changes). Reject anything else with `400`.

## Module layout

Multi-module Gradle build (Kotlin DSL), package root `com.chrisjenx.silo`:

```
:protocol          - CacheKey value class, content-types, errors (pure Kotlin)
:storage           - CacheStore + EvictionPolicy + Reservation interfaces, no impls
:storage-fs        - FileSystemCacheStore with 2-level sharded layout
:metadata-sqlite   - MetadataIndex impl over SQLite WAL
:metrics           - Micrometer/Prometheus wiring
:server            - Ktor app, routes, auth, config
:web               - Kobweb SPA (separate composite project under web/)
:bench             - kotlinx-benchmark/JMH
:test-fixtures     - shared kotest helpers (TmpCacheRoot, TestKeys, FakeClock)
```

The `:server` module never depends on Kobweb; the SPA is built independently and the static
export is copied into `:server/src/main/resources/static/admin/` at assembly time.

## Atomic write protocol (FS + SQLite)

The single most important invariant: **`kill -9` mid-PUT cannot corrupt the cache.**

1. Validate key → `MetadataIndex.reserve(size)` returns victim keys (async deleted).
2. Stream the body to `tmp.{key}.{uuid}` **in the same shard directory** as `final` — rename never crosses filesystems or overlay layers.
3. `fileChannel.force(true)` — data fsync.
4. `Files.move(tmp, final, ATOMIC_MOVE)` — catch `AtomicMoveNotSupportedException` and fall back to copy+delete with WARN (cross-FS root).
5. Parent dir fsync (config flag).
6. SQLite `UPSERT INTO cache_entry(...)` in a single transaction.
7. Eviction policy is notified; in-memory LRU mirror updated.

Concurrent PUTs of the same key are safe: content-addressable → identical bytes → atomic rename → last-writer-wins. **No per-key locks.** This is a deliberate decision; document if you change it.

## Storage caps & auto-cleanup

All caps enforced concurrently:

| Cap | Default | Trigger |
|---|---|---|
| `silo.storage.max-bytes` | 100 GB | LRU evict oldest by `last_access` |
| `silo.storage.max-entries` | 1,000,000 | Inode-exhaustion guard |
| `silo.storage.max-entry-bytes` | 2 GB | PUT > limit → `413` (early via `Expect: 100-continue`) |
| `silo.eviction.max-age-days` | 30 | TTL sweeper drops untouched entries |
| `silo.storage.reserved-free-bytes` | 5 GB | Stop accepting PUTs → `503` |
| `silo.storage.reserved-free-inodes` | 100,000 | Same, but for `df -i` |

**Eviction order**: TTL (age-first) → capacity-overflow (LRU) → emergency reserve. Budget-limited (`silo.eviction.max-deletes-per-cycle = 1000`) to avoid I/O storms. Background sweeper runs every 60s.

`last_access` updates are batched every 60s via a single SQLite UPDATE (cuts write volume 99%+).

## External interference handling

The server must remain consistent if another process modifies `cas/` out of band.

- Single-process lockfile: `.silo.lock` at root via `FileChannel.tryLock()`. Refuse to start if locked.
- On-read ENOENT fallback (bazel-remote pattern): SQLite says hit, disk says no → delete the row, log WARN, return 404 (looks like a cache miss to the client; self-heals).
- Periodic reconciliation sweep (default hourly): walks `cas/**` lazily; orphan blobs re-indexed, orphan rows deleted, stale `.tmp.*` files (>10 min) removed.
- Optional `silo.storage.verify-sha256-on-read = true` for tamper detection.
- **No `WatchService` / inotify** — it doesn't scale past 10K files and silently drops events.

## OS/FS/Ktor limits & guardrails

- Sharded layout `cas/{ab}/{cd}/{key}` keeps any one directory under ~15 entries at 1M total.
- NFS root is **unsupported**. Detect on Linux via `/proc/mounts` and refuse to start.
- Catch `ENOSPC`/`EDQUOT` on writes → unreserve, ERROR-log, return `503`.
- `ulimit -n` ≥ 65,536 in production. WARN at startup if < 4096.
- Netty `SO_BACKLOG = 512`, `requestReadTimeout = 60s` (slowloris).
- `Dispatchers.IO.limitedParallelism(64)` for storage ops + semaphore guard.
- Stream PUT bodies via `call.receiveChannel()` — never `receive<ByteArray>` (would buffer the whole body).

Full table: `docs/limits.md`.

## TDD expectations

Red → green → refactor. No production code without a failing test first, with two narrow exceptions: `application.conf` wiring and `main()` entrypoint.

- kotest 6, `BehaviorSpec` for HTTP/protocol, `StringSpec` for pure functions.
- Source sets split: `src/unitTest/kotlin` and `src/integrationTest/kotlin`. CI runs both.
- Every `CacheStore` implementation extends the shared `CacheStoreContractSpec` in `:test-fixtures`.
- kotest-property generators for fuzzing key parsing and store round-trips.
- Coverage gate: 80% line on `:protocol` and `:storage` via Kover.

## How to add a storage backend

1. Add module `storage-<name>` under `modules/`.
2. Implement `CacheStore` — all methods suspending, all blocking I/O wrapped in `withContext(Dispatchers.IO)`.
3. Implement `MetadataIndex` if the backend has its own metadata strategy (S3 may use object tags; otherwise re-use `:metadata-sqlite`).
4. Register via `META-INF/services/com.chrisjenx.silo.storage.BackendFactory`.
5. Extend `application.conf` with `silo.storage.<name>.*` keys and document them in `docs/configuration.md`.
6. Add a kotest subclass that extends `CacheStoreContractSpec`. **All tests must pass before the backend can ship.**

If the backend cannot honor a contract (S3 has no atomic rename, so it cannot guarantee single-byte-accurate concurrent PUTs), declare unsupported features via `supports(StorageFeature)` and have the contract spec skip those test cases — never silently downgrade behavior.

## Commands

Once `:server` and friends exist:

```bash
./gradlew :server:run                           # local dev server, hot reload
./gradlew test                                  # unit + integration tests
./gradlew test --tests "ClassName.methodName"   # single test
./gradlew :protocol:test                        # tests for one module
./gradlew :server:shadowJar                     # fat jar -> modules/server/build/libs/silo-*-all.jar
./gradlew ktlintCheck detekt                    # lint
./gradlew koverHtmlReport                       # coverage
./gradlew :bench:benchmark                      # JMH micro-benchmarks
./gradlew :web:kobwebExport                     # build admin SPA bundle
k6 run bench/k6/smoke.js                        # local load smoke
docker buildx build --platform linux/amd64,linux/arm64 -t silo:dev .
```

## Performance budget (CI-enforced via `bench.yml`)

- GET hit p99 < 50ms for 1MB on commodity SSD
- PUT p99 < 100ms for 1MB
- RSS < 200MB idle, < 500MB under 100rps mixed
- Regression > 10% on any tracked metric fails the build

## Security guard rails

- `CacheKey.parse` regex blocks path traversal **at the protocol boundary**. Never construct a filesystem path from raw user input below that layer.
- Request size cap → `413` via `Expect: 100-continue` short-circuit (no wasted body upload).
- bcrypt password hashes (`at.favre.lib:bcrypt`) with an in-memory verification cache keyed on `hash(plaintext)` (5-min TTL) so steady-state CI traffic doesn't re-bcrypt every request.
- TLS termination = reverse-proxy by default. Inline TLS is opt-in only.
- Body bytes **never logged.** Log only `cacheKey[0:12]`, `bytes`, `durationMs`, `outcome`.
- Constant-time auth comparison (`MessageDigest.isEqual` on bcrypt output).

## Release process

- Conventional Commits: `feat:`, `fix:`, `chore:`, `docs:`, `refactor:`, `test:`, `perf:`. Enforced via commitlint on PRs.
- `release-please` reads merged commits and opens a release PR with `CHANGELOG.md` + version bump. **Never hand-edit `CHANGELOG.md`.**
- Tagged release triggers `release.yml`: fat jar, multi-arch Docker to `ghcr.io`, CycloneDX SBOM, cosign keyless signing.
- Pre-1.0 (current): MINOR may carry breaking changes — call them out in the PR title (`feat!:`).

## Anti-patterns (CI / detekt blocks these)

- `!!` non-null assertion
- `runBlocking` outside `main` and tests
- Logging request/response bodies
- `Thread.sleep` in suspending functions
- Holding `Mutex` across I/O
- `receive<ByteArray>()` on a PUT body
- New dependencies on Spring, Jackson, Koin (in core), Hibernate, Exposed (for cache data)
- Editing `CHANGELOG.md` by hand

## References

- Bootstrap plan: `~/.claude-work/plans/project-goal-and-archetecture-mossy-naur.md`
- Design language: `docs/design.md`
- Operational limits: `docs/limits.md`
- HOCON config reference: `docs/configuration.md`
- Reverse-proxy / TLS: `docs/tls.md`
- Backup, restore, runbook: `docs/operations.md`
- Repo settings checklist: `docs/repo-setup.md`
