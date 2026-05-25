```
███████╗██╗██╗      ██████╗
██╔════╝██║██║     ██╔═══██╗
███████╗██║██║     ██║   ██║
╚════██║██║██║     ██║   ██║
███████║██║███████╗╚██████╔╝
╚══════╝╚═╝╚══════╝ ╚═════╝
```

> **Cache where you keep your grain.**

[![CI](https://github.com/chrisjenx/silo/actions/workflows/ci.yml/badge.svg)](https://github.com/chrisjenx/silo/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/chrisjenx/silo?include_prereleases&sort=semver)](https://github.com/chrisjenx/silo/releases)
[![Container](https://img.shields.io/badge/ghcr.io-silo-blue?logo=docker)](https://github.com/chrisjenx/silo/pkgs/container/silo)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/kotlin-2.x-7F52FF?logo=kotlin)](https://kotlinlang.org)
[![JVM](https://img.shields.io/badge/JVM-21%2B-orange)](https://adoptium.net)
[![Docs](https://img.shields.io/badge/docs-chrisjenx.github.io%2Fsilo-blue)](https://chrisjenx.github.io/silo/)
![Status](https://img.shields.io/badge/status-alpha-orange)

📖 **Documentation:** <https://chrisjenx.github.io/silo/>

## What is Silo?

Silo is a self-hosted HTTP server that speaks the Gradle build cache protocol. It is a drop-in
replacement for the now-EOL **Gradle Remote Build Cache Node**. Written in Kotlin on Ktor.
Ships as a fat jar or a multi-arch Docker image. Apache-2.0. Free forever.

## Why

Gradle Inc. has deprecated the free standalone Build Cache Node in favor of the paid
**Develocity** product. Teams who want a self-hostable, no-account-required, no-license-fee
build cache had no maintained option. Silo fills that gap.

- One binary. One volume. One port.
- No phone-home. No telemetry. No accounts.
- Atomic writes — `kill -9` mid-PUT cannot corrupt the cache.
- LRU + TTL + size + entry-count caps, all enforced together.
- Self-healing against out-of-band deletes.
- Prometheus metrics + retro-terminal admin dashboard.

## Features

- Full Gradle HTTP build cache protocol (`GET`, `PUT`, `HEAD`)
- Pluggable storage — filesystem in v0.1; S3 and GCS planned for v0.2
- HTTP Basic auth with read-only and read-write user split
- LRU eviction with TTL, byte-cap, entry-cap, and reserved-free-space thresholds
- Prometheus `/metrics`, JSON-structured logs, `/health` and `/ready`
- Retro-terminal admin SPA (Kobweb) — phosphor green, amber CRT, or paper-tape themes
- Atomic content-addressed writes with SQLite (WAL) metadata index
- Multi-arch Docker (linux/amd64, linux/arm64) and a single fat jar
- JVM 21+ — virtual threads, modern Ktor, no native image required

## Quick start

### Docker

```bash
docker run -d --name silo \
  -p 8080:8080 \
  -v silo-data:/data \
  ghcr.io/chrisjenx/silo:latest
```

### Fat jar

```bash
curl -L https://github.com/chrisjenx/silo/releases/latest/download/silo.jar -o silo.jar
java -jar silo.jar
```

### docker-compose

See [`examples/docker-compose.yml`](examples/docker-compose.yml). Includes a named volume,
env-var auth, and a sidecar Caddy reverse proxy for HTTPS.

## Configure Gradle to use Silo

In your project's `settings.gradle.kts`:

```kotlin
buildCache {
    remote<HttpBuildCache> {
        url = uri("http://localhost:8080/cache/")  // trailing slash is required
        push = true
        credentials {
            username = "ci-writer"
            password = System.getenv("SILO_CACHE_PASSWORD")
        }
    }
}
```

See [`examples/gradle-settings-snippet.kts`](examples/gradle-settings-snippet.kts) for the full snippet.

## Admin UI

Silo ships a single-page admin dashboard at `/admin`. The design language is intentionally
**terminal** — phosphor green on black, ASCII box-drawing, Unicode block sparklines, no
chart libraries. See [`docs/design.md`](docs/design.md) for the full spec.

**▶ [Try the live demo](https://chrisjenx.github.io/silo/demo/dashboard.html)** — the real admin
UI running on simulated data, right in your browser. No install, no backend.

## Configuration

Silo is configured via HOCON (`application.conf`) with environment-variable overrides.

| Env var | HOCON key | Default | Purpose |
|---|---|---|---|
| `SILO_PORT` | `ktor.deployment.port` | `8080` | HTTP listen port |
| `SILO_STORAGE_ROOT` | `silo.storage.filesystem.root` | `/var/lib/silo` | Data directory |
| `SILO_MAX_BYTES` | `silo.storage.max-bytes` | `107374182400` (100 GB) | Total cache size cap |
| `SILO_MAX_ENTRIES` | `silo.storage.max-entries` | `1000000` | Total entry cap |
| `SILO_MAX_ENTRY_BYTES` | `silo.storage.max-entry-bytes` | `2147483648` (2 GB) | Per-entry cap |
| `SILO_MAX_AGE_DAYS` | `silo.eviction.max-age-days` | `30` | TTL for untouched entries |
| `SILO_USERS_FILE` | `silo.auth.users-file` | `/etc/silo/users.conf` | bcrypt user list |
| `SILO_ANONYMOUS_READ` | `silo.auth.anonymous-read` | `true` | Allow unauthenticated GET/HEAD |

Full reference: [`docs/configuration.md`](docs/configuration.md). Hardware, requirements, and limits: [`docs/limits.md`](docs/limits.md).

## Documentation

The full docs site is published at **<https://chrisjenx.github.io/silo/>**.

- [Live dashboard demo](https://chrisjenx.github.io/silo/demo/dashboard.html) — the admin UI on simulated data, no install
- [Configuration reference](https://chrisjenx.github.io/silo/configuration.html) — every HOCON key and env override
- [Requirements & limits](https://chrisjenx.github.io/silo/limits.html) — hardware sizing, caps, FS, `ulimit`, NFS
- [Reverse proxy & TLS](https://chrisjenx.github.io/silo/tls.html) — termination at a proxy, inline TLS opt-in
- [Operations runbook](https://chrisjenx.github.io/silo/operations.html) — backup, restore, recovery
- [Design language](https://chrisjenx.github.io/silo/design.html) — the retro-terminal admin UI spec

## Comparison

| Feature | Silo v0.1 | Develocity Build Cache Node |
|---|---|---|
| License | Apache-2.0 | Commercial |
| Cost | Free | Per-seat |
| Self-host | Yes | Yes |
| Phone-home telemetry | No | Yes |
| Storage backends | Filesystem | Filesystem, S3 |
| Admin UI | Retro terminal | Standard |
| Atomic writes | Yes | Yes |
| TTL eviction | Yes | Yes |
| Multi-arch Docker | linux/amd64, linux/arm64 | linux/amd64 |
| Replication | Planned (v0.3+) | Yes |
| Resource footprint | ~150 MB RAM idle | ~1 GB+ |

*Not affiliated with or endorsed by Gradle Inc. or Gradle Build Tool®.*

## Status

Alpha. Tracking towards v0.1 walking-skeleton — see the
[v0.1 milestone](https://github.com/chrisjenx/silo/milestone/1) and
[v0.2 hardening](https://github.com/chrisjenx/silo/milestone/2).

## Building from source

```bash
./gradlew :server:shadowJar
java -jar modules/server/build/libs/silo-*-all.jar
```

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md). Good first issues are labelled
[`good-first-issue`](https://github.com/chrisjenx/silo/labels/good-first-issue).

## Security

See [`SECURITY.md`](SECURITY.md) for the vulnerability disclosure policy.

## License

Apache-2.0 — see [`LICENSE`](LICENSE).
