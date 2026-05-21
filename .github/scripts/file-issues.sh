#!/usr/bin/env bash
# Files all v0.1 and v0.2 issues into the chrisjenx/silo repo.
#
# Idempotent only at the milestone level — running twice creates duplicate issues.
# Use this once at bootstrap, then file new issues manually.
#
# Requires: gh CLI authenticated against the repo.

set -euo pipefail

REPO="${REPO:-chrisjenx/silo}"
M1="v0.1 Walking Skeleton"
M2="v0.2 Production Hardening"

mk() {
  local title="$1" milestone="$2" labels="$3" body="$4"
  gh issue create --repo "$REPO" --title "$title" --milestone "$milestone" --label "$labels" --body "$body"
}

############################################
# v0.1 — Walking Skeleton
############################################

# --- Foundation block (must land first) ---

mk "1. Scaffold Gradle multi-module project" "$M1" "area/build,priority/p0" "$(cat <<'EOF'
Set up the multi-module Gradle build that everything else depends on.

**Modules:** `:protocol`, `:storage`, `:server`, `:test-fixtures` (others added as their issues land).

**Acceptance**
- Root `settings.gradle.kts` + `build.gradle.kts` + `gradle/libs.versions.toml`
- `buildSrc/` convention plugins: `silo.kotlin-conventions`, `silo.ktor-conventions`, `silo.testing-conventions`
- JDK 21 toolchain, Kotlin 2.x stable
- Package root `com.chrisjenx.silo`
- `./gradlew tasks` lists all modules
- `./gradlew build` succeeds on empty modules

**Refs**
- CLAUDE.md "Module layout"
- `~/.claude-work/plans/project-goal-and-archetecture-mossy-naur.md`
EOF
)"

mk "1a. Test infra: kotest, source-set split, :test-fixtures skeleton" "$M1" "area/test,area/build,priority/p0" "$(cat <<'EOF'
Establish the testing harness every later issue extends.

**Acceptance**
- kotest 6 wired (`kotest-runner-junit5`, `kotest-assertions-core`, `kotest-property`)
- Source sets: `src/unitTest/kotlin` and `src/integrationTest/kotlin` per module, both run on `./gradlew check`
- `:test-fixtures` exports `TmpCacheRoot`, `TestKeys`, `FakeClock`
- Abstract `CacheStoreContractSpec` stub (impls extend it once `CacheStore` interface lands)
- Smoke `BehaviorSpec` proves kotest is wired

**Refs**
- CLAUDE.md "TDD expectations"
EOF
)"

mk "1b. Code quality: ktlint, detekt, kover" "$M1" "area/build,area/test,priority/p0" "$(cat <<'EOF'
Wire the style + static-analysis + coverage gates that CI enforces from day one.

**Acceptance**
- `ktlint` plugin, `./gradlew ktlintCheck` runs clean
- `detekt` with strict rules: no `!!`, no `runBlocking` outside `main`/tests, no body-logging via custom rule
- Kover with 80% line threshold on `:protocol` and `:storage`
- `./gradlew check` runs everything

**Refs**
- CLAUDE.md "Anti-patterns"
EOF
)"

mk "2. LICENSE + headers plugin check" "$M1" "area/build,priority/p0,good-first-issue" "$(cat <<'EOF'
LICENSE is already at repo root. Add a Gradle plugin that verifies every `.kt` file carries the Apache-2.0 header.

**Acceptance**
- License header check enforced on `./gradlew check`
- Auto-format task for missing headers
- Header text matches `LICENSE` notice block

**Refs**
- LICENSE
EOF
)"

mk "2a. CI workflow already in place — extend with Gradle build step" "$M1" "area/ci,priority/p0" "$(cat <<'EOF'
`.github/workflows/ci.yml` already exists with a bootstrap check that skips Gradle when there is no `gradlew`. Once #1 lands, the workflow runs `./gradlew build koverXmlReport ktlintCheck detekt` on matrix linux x64 + arm64.

**Acceptance**
- CI green on `main` post-#1 with all gradle tasks running
- JUnit annotations on PRs
- Kover XML uploaded as artifact
- Markdown lint + lychee link check pass

**Refs**
- `.github/workflows/ci.yml`
EOF
)"

mk "2b. Commit-lint workflow + PR template" "$M1" "area/ci,area/docs,priority/p0" "$(cat <<'EOF'
`.github/workflows/commitlint.yml` enforces Conventional Commits on PR titles. PR template already requires test evidence + DCO sign-off.

**Acceptance**
- Bad-title PR (uppercase / wrong type) is blocked
- Valid PR titles pass
- PR template checklist visible on PR creation

**Refs**
- `.github/workflows/commitlint.yml`
- `.github/PULL_REQUEST_TEMPLATE.md`
EOF
)"

mk "2c. Apply branch protection per docs/repo-setup.md" "$M1" "area/docs,area/ci,priority/p0" "$(cat <<'EOF'
Maintainer action: run the `gh` commands in `docs/repo-setup.md` to enable branch protection on `main`, tag protection on `v*`, merge settings, security scanning.

**Acceptance**
- `main` requires CI green + 1 review + linear history
- Force-push and direct deletion disabled
- `v*` tag protection on
- Dependabot + secret-scanning enabled

**Refs**
- `docs/repo-setup.md`
EOF
)"

mk "2d. Pre-commit hook + Gradle installGitHooks task" "$M1" "area/build,priority/p1,good-first-issue" "$(cat <<'EOF'
Local hook in `.githooks/pre-commit` runs ktlint format + detekt on staged Kotlin files. Gradle `installGitHooks` task configures `core.hooksPath`.

**Acceptance**
- `./gradlew installGitHooks` is documented in `CONTRIBUTING.md`
- Committing a file with a style violation is blocked locally
EOF
)"

# --- Storage core ---

mk "3. CacheStore interface + CacheStoreContractSpec" "$M1" "area/storage,area/test,priority/p0" "$(cat <<'EOF'
Define the `CacheStore` interface and the abstract kotest contract every backend extends.

**Acceptance**
- `CacheStore` interface in `:storage` with suspending `get/put/has/delete/stats` returning `Source`/`Sink` for streaming
- `PutOutcome` sealed type (Stored / AlreadyPresent / RejectedTooLarge)
- `CacheReadHandle` for streaming GETs
- `CacheStoreContractSpec` in `:test-fixtures` covering: round-trip, 404, concurrent PUT same key, malformed key, oversized body, AlreadyPresent semantics

**Refs**
- Plan §"Core types"
- CLAUDE.md "Module layout"
EOF
)"

mk "4. FileSystemCacheStore with sharded layout + atomic rename" "$M1" "area/storage,priority/p0" "$(cat <<'EOF'
Implement the filesystem backend.

**Acceptance**
- 2-level hex sharding `cas/{ab}/{cd}/{key}`
- Temp file in same shard dir, `fileChannel.force(true)` → `Files.move(ATOMIC_MOVE)`
- Parent dir fsync (configurable)
- `AtomicMoveNotSupportedException` → copy+delete with WARN
- Random UUID suffix on temp file (concurrent PUT safety)
- Extends `CacheStoreContractSpec`, all tests pass

**Refs**
- Plan §"Atomic write protocol"
- CLAUDE.md "Atomic write protocol"
EOF
)"

mk "4a. MetadataIndex SQLite WAL implementation" "$M1" "area/storage,priority/p0" "$(cat <<'EOF'
SQLite metadata index replacing per-blob sidecar files.

**Acceptance**
- `xerial:sqlite-jdbc` dependency
- Pragmas at startup: `journal_mode=WAL`, `synchronous=NORMAL`, `busy_timeout=5000`, `cache_size=-65536`, `temp_store=MEMORY`, `mmap_size=268435456`
- Schema v1 per plan (cache_entry + schema_version + indexes)
- Batched `last_access` flush every 60s via single UPDATE
- Migrations via `schema_version` table
- Unit + property tests in `:metadata-sqlite`

**Refs**
- Plan §"SQLite metadata index"
EOF
)"

mk "4b. Single-process lockfile via FileChannel.tryLock" "$M1" "area/storage,area/security,priority/p0" "$(cat <<'EOF'
Prevent two Silo instances from corrupting one data root.

**Acceptance**
- `.silo.lock` created at root, held for process lifetime via `FileChannel.tryLock()`
- Lock released on JVM shutdown hook
- Second start refuses with `ERR: storage root locked by PID N`
- Test: spawn 2 processes, second exits non-zero

**Refs**
- Plan §"External interference handling"
EOF
)"

mk "4c. NFS root detection — refuse to start" "$M1" "area/storage,priority/p1" "$(cat <<'EOF'
NFS is unsupported. Detect via `/proc/mounts` (Linux) and refuse to start with clear error.

**Acceptance**
- Linux: parse `/proc/self/mountinfo`; if root is on `nfs`/`nfs4`, abort
- macOS / Windows: best-effort detection, WARN if cannot determine
- Configurable override `silo.storage.allow-unsupported-fs = false` (default false)
- Documented in `docs/limits.md`

**Refs**
- `docs/limits.md`
EOF
)"

mk "4d. Cross-FS rename fallback" "$M1" "area/storage,priority/p1" "$(cat <<'EOF'
Catch `AtomicMoveNotSupportedException` and fall back to copy+delete with WARN log.

**Acceptance**
- Test exercises a cross-FS root (tmpfs + tmpdir)
- WARN log includes both source and target filesystems
- Counter `silo_storage_cross_fs_rename_total` incremented
EOF
)"

# --- HTTP layer ---

mk "5. Ktor app skeleton + /health + /ready" "$M1" "area/protocol,priority/p0" "$(cat <<'EOF'
Minimal Ktor app, Netty engine, lifecycle probes.

**Acceptance**
- `Application.module` wired
- `/health` always 200 once JVM is up
- `/ready` 200 when SQLite open + storage root writable
- HOCON config loading (`application.conf`)
- Logback + logstash-encoder JSON logs
- Startup banner with version + commit SHA
EOF
)"

mk "6. GET /cache/{key}" "$M1" "area/protocol,priority/p0" "$(cat <<'EOF'
Read path. Streams blob from CacheStore via `respondBytesWriter`.

**Acceptance**
- 200 + body on hit
- 404 on miss (NOT an error)
- `Content-Type: application/vnd.gradle.build-cache-artifact.v1`
- `Content-Length` set
- ENOENT fallback: SQLite hit but disk miss → purge row, return 404 (see #11c)
- Integration test: round-trip 1KB, 1MB, 100MB

**Refs**
- CLAUDE.md "Gradle Build Cache Protocol"
EOF
)"

mk "7. PUT /cache/{key}" "$M1" "area/protocol,priority/p0" "$(cat <<'EOF'
Write path. Streams body via `call.receiveChannel()` into CacheStore.

**Acceptance**
- 200 on store
- Idempotent for same content-addressed key
- Reservation taken before stream starts
- Body never buffered in heap (verify via streaming test)
- Integration test: 100MB PUT does not grow heap
EOF
)"

mk "8. HEAD /cache/{key}" "$M1" "area/protocol,priority/p1" "$(cat <<'EOF'
Cheap existence check. Same auth + ENOENT semantics as GET.

**Acceptance**
- 200 with `Content-Length` on hit, no body
- 404 on miss
- Identical drift fallback as GET
EOF
)"

mk "9. CacheKey path-traversal guard" "$M1" "area/security,priority/p0" "$(cat <<'EOF'
`CacheKey` value class with strict regex validation at the protocol boundary.

**Acceptance**
- Regex `^[a-f0-9]{8,128}$`
- Rejects `../`, `/`, null byte, Unicode normalisation tricks, uppercase, whitespace
- Routing layer returns 400 on invalid before storage sees it
- Property test: 10k random non-matching strings all return null
EOF
)"

mk "10. Basic Auth + read/write user split + bcrypt" "$M1" "area/auth,priority/p1" "$(cat <<'EOF'
HTTP Basic auth with role-split (read vs write). bcrypt hashes only.

**Acceptance**
- Ktor `authentication { basic }` configured
- `anonymous-read = true` toggle: GET/HEAD work without creds, PUT requires WRITE role
- `anonymous-read = false`: all routes require auth
- bcrypt verification via `at.favre.lib:bcrypt`
- **In-memory verification cache** keyed on `hash(plaintext)` not plaintext, 5-min TTL — required because bcrypt cost-12 is ~250ms
- Constant-time comparison (`MessageDigest.isEqual`)
- `users.conf` reload on file change
- CLI `silo hash-password` subcommand
EOF
)"

# --- Storage caps / eviction / drift ---

mk "11. Eviction engine: LRU + byte-cap + entry-cap + reserved-free thresholds" "$M1" "area/storage,priority/p1" "$(cat <<'EOF'
Multi-cap eviction enforced concurrently. Budget-limited deletes.

**Acceptance**
- Caps from `silo.storage.{max-bytes,max-entries,max-entry-bytes,reserved-free-bytes,reserved-free-inodes}`
- LRU by `last_access_ms` in SQLite
- Budget `silo.eviction.max-deletes-per-cycle = 1000`
- Background sweeper every 60s
- Reservation channel: PUT reserves bytes synchronously, eviction happens async
- `silo_store_evictions_total{reason}` metric

**Refs**
- Plan §"Storage caps & auto-cleanup"
EOF
)"

mk "11a. TTL sweeper (max-age-days)" "$M1" "area/storage,priority/p1" "$(cat <<'EOF'
Drop entries untouched for more than `silo.eviction.max-age-days` regardless of capacity.

**Acceptance**
- Background coroutine, batched per cycle
- Default 30 days; `max-age-days = 0` disables
- Runs **before** capacity-LRU each cycle (age-first ordering)
- Metric: `silo_store_evictions_total{reason="ttl"}`
EOF
)"

mk "11b. Reconciliation sweep + POST /api/storage/reconcile" "$M1" "area/storage,priority/p1" "$(cat <<'EOF'
Periodic walk of `cas/**` to reconcile against SQLite. Self-heal drift.

**Acceptance**
- Lazy walk in `silo.reconcile.batch-size` chunks (default 5000), yields between batches
- Drift cases handled: orphan blob → re-insert; orphan row → delete; stale `.tmp.*` >10min → delete
- Default interval 60 min; configurable
- Force-trigger via `POST /api/storage/reconcile` (admin auth)
- Counter `silo_drift_detected_total{kind}`

**Refs**
- Plan §"External interference handling"
EOF
)"

mk "11c. On-read ENOENT fallback (drift self-heal)" "$M1" "area/storage,priority/p0" "$(cat <<'EOF'
SQLite says hit, disk says no — purge the row, return 404, increment metric. Out-of-band deletes look like cache misses and self-heal.

**Acceptance**
- GET / HEAD share the path
- Row deleted in same transaction as the metric increment
- WARN log with key prefix (first 12 chars), no body
- Test: out-of-band `rm` of a blob → next GET returns 404, SQLite row gone
EOF
)"

mk "11d. ENOSPC / EDQUOT / inode-exhaustion handling" "$M1" "area/storage,area/observability,priority/p1" "$(cat <<'EOF'
Catch storage exhaustion mid-write, unreserve, return 503, emit metrics.

**Acceptance**
- Catches `IOException` matching ENOSPC / EDQUOT (parse message or use `errno` via `Files.getFileStore`)
- Reservation rolled back
- 503 returned (Gradle client will retry)
- `silo_storage_errors_total{kind="enospc|edquot|reserved_free_bytes|reserved_free_inodes"}` counter
- Reserved thresholds checked **before** accepting a PUT (early 503)
EOF
)"

mk "11e. max-entry-bytes → 413 with Expect: 100-continue early reject" "$M1" "area/protocol,area/storage,priority/p1" "$(cat <<'EOF'
Reject oversized PUTs before consuming the body.

**Acceptance**
- `Content-Length` > limit → 413 immediately
- `Expect: 100-continue` request: respond 413 instead of 100 (verified via integration test)
- Chunked transfer without Content-Length: track bytes, abort at limit, return 413
- Metric `silo_cache_puts_total{outcome="rejected_too_large"}`
EOF
)"

# --- Observability ---

mk "12. Prometheus metrics endpoint" "$M1" "area/observability,priority/p1" "$(cat <<'EOF'
Micrometer + `/metrics` Prometheus scrape endpoint.

**Acceptance**
- Counters: `silo_cache_{hits,misses,puts,bytes}_total`, `silo_store_evictions_total{reason}`, `silo_storage_errors_total{kind}`, `silo_drift_detected_total{kind}`, `silo_auth_failures_total{reason}`
- Gauges: `silo_store_size_bytes`, `silo_store_entries`, `silo_active_streams{direction}`, `silo_eviction_queue_depth`
- Histogram: `silo_request_duration_seconds{route,method,status}` with p50/p95/p99/p999
- JVM metrics enabled
- Common tags `env`, `instance`
EOF
)"

# --- Admin API ---

mk "13. Admin API /api/stats" "$M1" "area/admin,priority/p1" "$(cat <<'EOF'
Aggregated stats endpoint for the dashboard.

**Acceptance**
- JSON via kotlinx.serialization
- Fields: hits, misses, bytes_in, bytes_out, hit_rate, top-N hot keys (last hour)
- Requires READ role (or anonymous if anonymous-read=true)
- CORS allowed for admin origins only
EOF
)"

mk "14. Admin API /api/storage" "$M1" "area/admin,priority/p2" "$(cat <<'EOF'
Storage stats: total size, entry count, free disk + inodes, oldest entry, newest entry, top-N largest, eviction queue depth.
EOF
)"

mk "15. Admin API /api/config (redacted)" "$M1" "area/admin,priority/p2" "$(cat <<'EOF'
Read-only effective config snapshot, with every `*-password`, `*-pepper`, `*-secret*`, `*-key` redacted to `***`.

**Acceptance**
- Test verifies every redaction pattern
- Output is HOCON-rendered string + parsed JSON tree
EOF
)"

# --- Web ---

mk "16. Kobweb scaffold under web/" "$M1" "area/web,priority/p1" "$(cat <<'EOF'
Separate Kobweb composite project, static export integrated with `:server` resources at build time.

**Acceptance**
- `web/` Kobweb project boots in dev mode
- `kobwebExport` produces static SPA
- Gradle task copies export into `:server/src/main/resources/static/admin/`
- Fat jar serves `/admin/*` from static resources
EOF
)"

mk "17. Terminal-aesthetic CSS token layer (3 themes)" "$M1" "area/web,priority/p1" "$(cat <<'EOF'
Implement design tokens from `docs/design.md` §"Visual language — themes" as CSS custom properties + Kotlin `SiloTokens` object.

**Acceptance**
- 3 themes: phosphor (default), amber, paper
- Theme switch persisted in `localStorage`
- All token pairs achieve WCAG AAA contrast (verified in a unit test that parses computed styles)

**Refs**
- `docs/design.md`
EOF
)"

mk "18. Dashboard page" "$M1" "area/web,priority/p1" "$(cat <<'EOF'
Implement the `/` dashboard: 6 stat tiles + 1 sparkline + recent activity table. Wired to `/api/stats`.

**Refs**
- `docs/design.md` §"Pages"
EOF
)"

mk "19. Splash + ASCII wordmark + 404 page" "$M1" "area/web,priority/p2,good-first-issue" "$(cat <<'EOF'
First-paint splash, about page, and a 404 — all with the ANSI Shadow figlet wordmark from `docs/design.md` §"Brand splash".

**Acceptance**
- Splash flashes for ~400ms, then routes to `/`
- About shows version + uptime + commit SHA + license
- 404 page is themed
EOF
)"

# --- Perf / release plumbing ---

mk "20. k6 smoke + load tests (cold-put, hot-get, mixed, large-artifact)" "$M1" "area/perf,priority/p1" "$(cat <<'EOF'
Scripts under `bench/k6/`:
- `cold-put.js` — 5000 distinct keys, 1 MiB each, conc 50
- `hot-get.js` — 100 prewarmed keys, 10k GETs at conc 200
- `mixed.js` — 70/30 GET/PUT against a 90% hot working set
- `large-artifact.js` — 50 PUTs of 500 MiB, verify no heap growth
- `smoke.js` — minimal sanity for CI

**Acceptance**
- All scripts pass against local Docker container
- `mixed.js` is the regression gate target for `bench.yml`
EOF
)"

mk "21. kotlinx-benchmark JMH micro-benchmarks" "$M1" "area/perf,priority/p2" "$(cat <<'EOF'
`:bench` module benchmarks:
- `CacheKeyValidationBench` — target > 50M ops/sec/core
- `ShardingBench` — allocation rate of path resolution
- `LruIndexBench` — contention 1/4/16/64 threads
- `AtomicWriterBench` — tmp + rename simulation

Run: `./gradlew :bench:benchmark`. Output JSON to `bench/results/`.
EOF
)"

mk "22. Multi-arch Dockerfile + buildx" "$M1" "area/release,priority/p0" "$(cat <<'EOF'
Dockerfile already at repo root. Verify it builds against the Gradle scaffold once #1 lands.

**Acceptance**
- `docker buildx build --platform linux/amd64,linux/arm64 .` succeeds
- Image runs under non-root `silo` user
- `/data` volume declared
- HEALTHCHECK exercises `/health`
- OCI labels populated from build args
EOF
)"

mk "23. Extend ci.yml: k6 smoke step once routes ship" "$M1" "area/ci,area/perf,priority/p1" "$(cat <<'EOF'
Once #6 and #7 are merged, extend `ci.yml` to:
- Build the fat jar
- Boot it with a tmpfs data root
- Run `bench/k6/smoke.js` against it
- Fail the build on non-zero k6 exit
EOF
)"

mk "24. release.yml hardening: SBOM + cosign + provenance" "$M1" "area/release,priority/p0" "$(cat <<'EOF'
`.github/workflows/release.yml` is already in place. Verify end-to-end on the first `v0.1.0-alpha.1` tag.

**Acceptance**
- Multi-arch image pushed to ghcr.io
- Image signed with cosign (keyless, OIDC)
- CycloneDX SBOM attached to GitHub Release
- Fat jar attested with `actions/attest-build-provenance`
- SHA-256 checksums file in the release
EOF
)"

mk "25. bench.yml regression gate end-to-end" "$M1" "area/ci,area/perf,priority/p2" "$(cat <<'EOF'
`.github/workflows/bench.yml` already exists. Implement the missing pieces:
- `:bench:compareBaseline` Gradle task
- Baseline persistence in `bench/baselines/main.json` (committed by the workflow on green main)
- PR comment with p50/p95/p99 table
- Fail PR if any metric regresses > 10%
EOF
)"

mk "26. Renovate config tuning" "$M1" "area/build,priority/p2,good-first-issue" "$(cat <<'EOF'
`renovate.json` already at repo root. Onboard the bot:

**Acceptance**
- Renovate app installed on the repo
- First onboarding PR merged
- Weekly minor+patch group PR opens on schedule
- Ktor and Kotlin grouped separately for human review
EOF
)"

mk "27. release-please workflow wiring" "$M1" "area/release,priority/p1" "$(cat <<'EOF'
`.release-please-config.json` + `.release-please-manifest.json` + workflow already in place. Verify it opens a release PR after the first conventional commits land.

**Acceptance**
- Release PR appears after merging a `feat:` commit
- CHANGELOG.md generated and looks right
- Merging the release PR triggers `release.yml` via the tag
EOF
)"

mk "28. Fat-jar with embedded version + silo --version flag" "$M1" "area/release,priority/p1" "$(cat <<'EOF'
Embed Gradle `version` into `MANIFEST.MF` Implementation-Version. Read at runtime via `Package.getImplementationVersion()`. CLI flag prints `silo <version> (<git-sha>) jvm <javaVersion>`.

**Acceptance**
- `java -jar silo.jar --version` works
- Healthcheck endpoint includes the version
EOF
)"

# --- Docs (bootstrap session delivered these; tracking closure here) ---

mk "29. README.md (full content)" "$M1" "area/docs,priority/p0" "$(cat <<'EOF'
README delivered by the bootstrap commit. Close this issue referencing that commit.

**Refs**
- README.md
EOF
)"

mk "30. design.md (full content)" "$M1" "area/docs,area/web,priority/p1" "$(cat <<'EOF'
`docs/design.md` delivered by the bootstrap commit. Close referencing it.
EOF
)"

mk "31. CONTRIBUTING + CoC + SECURITY" "$M1" "area/docs,priority/p1,good-first-issue" "$(cat <<'EOF'
Delivered by the bootstrap commit. Close referencing the files.
EOF
)"

mk "31a. docs/limits.md" "$M1" "area/docs,priority/p1" "$(cat <<'EOF'
Delivered by bootstrap commit. Future updates: revise as new filesystems / OS versions are validated.
EOF
)"

mk "31b. docs/operations.md" "$M1" "area/docs,priority/p2" "$(cat <<'EOF'
Delivered by bootstrap commit. Future updates: add monitoring dashboards, alert recipes.
EOF
)"

mk "31c. Chaos test: external rm -rf cas/{ab} → self-heal on next GET" "$M1" "area/storage,area/test,priority/p1" "$(cat <<'EOF'
Integration test that exercises the drift self-heal path end-to-end.

**Acceptance**
- Spin up Silo, PUT 100 keys
- Delete one shard dir directly via `rm -rf`
- Next GET for an affected key returns 404
- SQLite rows for affected keys are gone
- `silo_drift_detected_total` incremented
EOF
)"

mk "31d. Chaos test: blob corruption with verify-sha256-on-read" "$M1" "area/security,area/test,priority/p2" "$(cat <<'EOF'
With `silo.storage.verify-sha256-on-read = true`, a blob whose content has been tampered with returns 404 and increments `silo_corruption_detected_total`.

**Acceptance**
- PUT a key, then overwrite the blob on disk with garbage of same length
- GET returns 404
- Row purged
- Counter incremented
EOF
)"

############################################
# v0.2 — Production Hardening
############################################

mk "32. Doc: TLS termination via reverse proxy" "$M2" "area/docs,area/security" "$(cat <<'EOF'
`docs/tls.md` already exists. v0.2 work: expand with Traefik full example, AWS ALB, Cloudflare Tunnel.
EOF
)"

mk "33. S3 storage backend" "$M2" "area/storage,priority/p1" "$(cat <<'EOF'
`storage-s3` module using AWS SDK v2. Lifecycle-policy-driven eviction (no atomic rename needed).

**Acceptance**
- Extends `CacheStoreContractSpec` (skipping eviction tests via feature flag)
- Multipart upload for >5MB
- Works against MinIO + R2 + S3
- Documented config keys
EOF
)"

mk "34. GCS storage backend" "$M2" "area/storage,priority/p1" "$(cat <<'EOF'
Analogous to S3 backend using `google-cloud-storage`.
EOF
)"

mk "35. OIDC / Bearer-token auth option" "$M2" "area/auth,priority/p1" "$(cat <<'EOF'
OAuth2 resource-server mode. Configurable issuer URL. Coexists with Basic.

**Acceptance**
- JWKS endpoint discovery + key rotation
- Token claims map to read/write roles
- Documented in `docs/configuration.md`
EOF
)"

mk "36. Spike: multi-node replication design" "$M2" "area/storage,priority/p2,needs-design" "$(cat <<'EOF'
ADR comparing gossip vs leader-based vs object-store-as-source-of-truth. Output: `docs/adr/0001-replication.md`.
EOF
)"

mk "37. SSE /api/stream/stats for live dashboard" "$M2" "area/admin,area/web" "$(cat <<'EOF'
Server-Sent Events stream emitting a JSON snapshot per second. Dashboard subscribes for live tile updates.
EOF
)"

mk "38. Audit log for admin actions" "$M2" "area/security,area/observability" "$(cat <<'EOF'
Append-only JSONL audit log of admin-API mutations. Rotated daily. Configurable path.
EOF
)"

mk "39. Structured JSON logs" "$M2" "area/observability" "$(cat <<'EOF'
Logback + logstash-encoder JSON layout. Already in scope for v0.1 — this issue tracks polish: log sampling, MDC propagation across coroutines.
EOF
)"

mk "40. Chaos test: kill -9 mid-PUT" "$M2" "area/storage,area/test" "$(cat <<'EOF'
Harness that sends a partial PUT, force-kills the server, restarts, asserts no half-written entry is visible to clients and no orphan tmp files remain.
EOF
)"

mk "41. Recovery scan on startup (orphan .tmp cleanup)" "$M2" "area/storage" "$(cat <<'EOF'
On boot, scan `cas/**` for `.tmp.*` files older than 10 minutes, delete, report count via `silo_recovery_orphans_cleaned_total`.
EOF
)"

mk "42. SBOM publish to Dependency-Track" "$M2" "area/security,area/release" "$(cat <<'EOF'
Push CycloneDX SBOMs from `release.yml` to a configurable Dependency-Track instance via secret.
EOF
)"

mk "43. Streaming zstd compression for entries > 1MB" "$M2" "area/perf,priority/p2" "$(cat <<'EOF'
Optional `Content-Encoding: zstd` negotiation. Bench before merge to confirm wall-clock improvement on real workloads.
EOF
)"

mk "44. SQLite vacuum schedule + WAL checkpoint tuning" "$M2" "area/storage,area/perf" "$(cat <<'EOF'
Periodic `PRAGMA wal_checkpoint(TRUNCATE)` and occasional `VACUUM`. Tune frequency vs. cache size.
EOF
)"

mk "45. xattr-based SHA256 storage (alt to SQLite column)" "$M2" "area/storage,priority/p3" "$(cat <<'EOF'
On Linux/macOS, store the content hash in the file's `user.silo.sha256` xattr instead of the SQLite column. Cheaper to verify, survives SQLite loss.

**Acceptance**
- Feature-flagged
- Falls back to SQLite on filesystems that don't support xattrs
EOF
)"

mk "46. Windows long-path (\\\\?\\) opt-in" "$M2" "area/storage,priority/p3" "$(cat <<'EOF'
For deep storage roots on Windows, prepend `\\?\` to paths to bypass MAX_PATH=260. Document opt-in via `silo.storage.filesystem.windows-long-paths = true`.
EOF
)"

mk "47. Replication via SQLite logical log shipping (spike)" "$M2" "area/storage,needs-design" "$(cat <<'EOF'
Spike: explore litestream or session-extension based replication for SQLite. Compare against blob-store-as-truth.
EOF
)"

echo "Done."
