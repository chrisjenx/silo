---
title: Home
nav_order: 1
---

# Silo

**Silo** is an OSS Kotlin/Ktor replacement for the end-of-life Gradle Remote Build Cache
Node — a drop-in HTTP server that speaks the [Gradle build cache protocol](https://docs.gradle.org/current/userguide/build_cache.html#sec:build_cache_configure_remote).
Fat-jar and multi-arch Docker, Apache-2.0.

## Quickstart

### Run with Docker

```bash
docker run -p 8080:8080 -v silo-data:/data ghcr.io/chrisjenx/silo:latest
```

### Run the fat jar

```bash
java -jar silo-*-all.jar          # listens on :8080, stores under /data
java -jar silo-*-all.jar --version
```

### Point Gradle at it

In your build's `settings.gradle.kts`:

```kotlin
buildCache {
    remote<HttpBuildCache> {
        url = uri("http://localhost:8080/cache/")
        isPush = true
        // credentials { username = "ci"; password = System.getenv("SILO_PW") }
    }
}
```

Then build with the cache enabled:

```bash
./gradlew build --build-cache
```

A cold build **PUT**s task outputs into Silo; a clean rebuild resolves them **FROM-CACHE**.
Health is at `/health`, Prometheus metrics at `/metrics`, and a live stats stream at
`/api/stream/stats`.

## Documentation

| Page | What's in it |
|---|---|
| [Configuration](configuration.md) | HOCON reference + every `SILO_*` env var |
| [Operations](operations.md) | Runbook: monitoring, tuning, backup/restore, audit log |
| [TLS & reverse proxy](tls.md) | Caddy, nginx, Traefik, AWS ALB, Cloudflare Tunnel |
| [Limits](limits.md) | OS / filesystem / Ktor guard rails |
| [Design language](design.md) | Admin SPA design notes |
| [Repository setup](repo-setup.md) | Reproduce the repo's settings on a fork |

## Status

Pre-1.0. The Gradle build-cache protocol, filesystem store, SQLite metadata index, auth
(Basic + OIDC), metrics, audit logging, and crash-recovery are implemented. See the
[issue tracker](https://github.com/chrisjenx/silo/issues) for what's next.
