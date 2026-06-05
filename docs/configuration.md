---
title: Configuration
nav_order: 4
---

# Silo — Configuration Reference

Silo is configured via HOCON (`application.conf`) with environment-variable overrides. Defaults are sensible; override only what your deployment needs.

## Loading order

1. `application.conf` bundled in the jar (defaults).
2. `application.conf` on the classpath of the running process (override file, optional).
3. Environment variables (highest precedence).

To pin a config file outside the classpath, set `-Dconfig.file=/path/to/silo.conf` on the JVM.

## Full reference

```hocon
ktor {
  deployment {
    port = 8080
    port = ${?SILO_PORT}
    host = "0.0.0.0"
    host = ${?SILO_HOST}
  }
  application {
    modules = [ com.chrisjenx.silo.server.ApplicationKt.module ]
  }
}

silo {
  server {
    port = 8080
    port = ${?SILO_PORT}
    host = "0.0.0.0"
    host = ${?SILO_HOST}
  }

  storage {
    root = "/data"
    root = ${?SILO_STORAGE_ROOT}

    # Total cache size cap (bytes). LRU evicts oldest by last_access until under cap.
    max-bytes = 107374182400                     # 100 GiB
    max-bytes = ${?SILO_MAX_BYTES}

    # Total entry cap. Inode-exhaustion guard for small-artifact workloads.
    max-entries = 1000000
    max-entries = ${?SILO_MAX_ENTRIES}

    # Per-entry cap. PUT > limit returns 413 (early via Expect: 100-continue).
    max-entry-bytes = 2147483648                 # 2 GiB
    max-entry-bytes = ${?SILO_MAX_ENTRY_BYTES}

    # Stop accepting PUTs (503) below these free-space thresholds.
    reserved-free-bytes = 5368709120             # 5 GiB
    reserved-free-bytes = ${?SILO_RESERVED_FREE_BYTES}
    reserved-free-inodes = 100000
    reserved-free-inodes = ${?SILO_RESERVED_FREE_INODES}

    # Refuse to start on NFS unless this is true (then WARN and proceed).
    allow-unsupported-fs = false
    allow-unsupported-fs = ${?SILO_ALLOW_UNSUPPORTED_FS}

    # Opt-in content-hash verification on every read. Costs ~1 GB/s CPU.
    verify-sha256-on-read = false
    verify-sha256-on-read = ${?SILO_VERIFY_SHA256_ON_READ}
  }

  eviction {
    # TTL: drop entries untouched for this many days. Set to 0 to disable.
    max-age-days = 30
    max-age-days = ${?SILO_MAX_AGE_DAYS}

    # Throttle to avoid I/O storms on big sweeps.
    max-deletes-per-cycle = 1000
    max-deletes-per-cycle = ${?SILO_MAX_DELETES_PER_CYCLE}

    # Background sweeper cadence (seconds).
    sweep-interval-seconds = 60
    sweep-interval-seconds = ${?SILO_EVICTION_SWEEP_INTERVAL_SECONDS}
  }

  sqlite {
    # Periodic WAL checkpoint (TRUNCATE) bounds the -wal file; occasional
    # VACUUM reclaims pages after large evictions.
    checkpoint-interval-seconds = 300
    checkpoint-interval-seconds = ${?SILO_SQLITE_CHECKPOINT_INTERVAL_SECONDS}
    vacuum-interval-seconds = 86400
    vacuum-interval-seconds = ${?SILO_SQLITE_VACUUM_INTERVAL_SECONDS}
  }

  auth {
    # When true, GET/HEAD work without credentials. PUT always requires write.
    anonymous-read = true
    anonymous-read = ${?SILO_ANONYMOUS_READ}

    # bcrypt user list (see "Users file format" below). Unset = no Basic users;
    # set anonymous-read = false alongside a users file to fully lock the cache.
    users-file = ${?SILO_USERS_FILE}

    # OAuth2 resource-server (OIDC / Bearer) mode. Coexists with Basic: a
    # request may present either an Authorization: Basic or Bearer header.
    oidc {
      # Off by default. When on, issuer + jwks-url are required.
      enabled = false
      enabled = ${?SILO_OIDC_ENABLED}

      # Expected `iss` claim and the JWKS endpoint to fetch signing keys from.
      # Keys are cached and refreshed on rotation (Nimbus JWKSource). RS256.
      issuer = ${?SILO_OIDC_ISSUER}
      jwks-url = ${?SILO_OIDC_JWKS_URL}

      # Optional `aud` claim to require. Omit to skip audience checks.
      audience = ${?SILO_OIDC_AUDIENCE}

      # Claim carrying roles/scopes. Accepts an array (["a","b"]) or a
      # space/comma-delimited string (e.g. the standard `scope` claim).
      roles-claim = "roles"
      roles-claim = ${?SILO_OIDC_ROLES_CLAIM}

      # Claim values that grant each role (empty by default). WRITE implies READ.
      read-roles = []                              # e.g. [ "cache:read" ]
      write-roles = []                             # e.g. [ "cache:write" ]
    }
  }

  audit {
    # Append-only JSONL audit log of admin-API mutations (e.g. reconcile),
    # written to `<dir>/audit-<UTC-date>.jsonl` and rotated daily.
    enabled = false
    enabled = ${?SILO_AUDIT_ENABLED}
    dir = "/data/audit"
    dir = ${?SILO_AUDIT_DIR}
  }
}
```

### Fixed (not configurable)

A few things are deliberately not config keys:

- **SQLite metadata DB path** — always `<storage root>/silo.db` (plus `-wal`/`-shm`).
- **`/metrics` and `/admin` paths** — fixed; the Prometheus scrape and admin SPA mount points are not relocatable.
- **Reconciliation** — runs at startup (orphan-`tmp` cleanup) and on demand via `POST /api/storage/reconcile`; there is no periodic reconcile loop.
- **bcrypt verify cache** — a 5-minute in-memory TTL, not tunable.

## Env-var quick reference

| Env var | HOCON path | Default |
|---|---|---|
| `SILO_PORT` | `silo.server.port` | `8080` |
| `SILO_HOST` | `silo.server.host` | `0.0.0.0` |
| `SILO_STORAGE_ROOT` | `silo.storage.root` | `/data` |
| `SILO_MAX_BYTES` | `silo.storage.max-bytes` | `107374182400` (100 GiB) |
| `SILO_MAX_ENTRIES` | `silo.storage.max-entries` | `1000000` |
| `SILO_MAX_ENTRY_BYTES` | `silo.storage.max-entry-bytes` | `2147483648` (2 GiB) |
| `SILO_RESERVED_FREE_BYTES` | `silo.storage.reserved-free-bytes` | `5368709120` (5 GiB) |
| `SILO_RESERVED_FREE_INODES` | `silo.storage.reserved-free-inodes` | `100000` |
| `SILO_ALLOW_UNSUPPORTED_FS` | `silo.storage.allow-unsupported-fs` | `false` |
| `SILO_VERIFY_SHA256_ON_READ` | `silo.storage.verify-sha256-on-read` | `false` |
| `SILO_MAX_AGE_DAYS` | `silo.eviction.max-age-days` | `30` |
| `SILO_MAX_DELETES_PER_CYCLE` | `silo.eviction.max-deletes-per-cycle` | `1000` |
| `SILO_EVICTION_SWEEP_INTERVAL_SECONDS` | `silo.eviction.sweep-interval-seconds` | `60` |
| `SILO_SQLITE_CHECKPOINT_INTERVAL_SECONDS` | `silo.sqlite.checkpoint-interval-seconds` | `300` |
| `SILO_SQLITE_VACUUM_INTERVAL_SECONDS` | `silo.sqlite.vacuum-interval-seconds` | `86400` |
| `SILO_ANONYMOUS_READ` | `silo.auth.anonymous-read` | `true` |
| `SILO_USERS_FILE` | `silo.auth.users-file` | _(unset — no Basic users)_ |
| `SILO_OIDC_ENABLED` | `silo.auth.oidc.enabled` | `false` |
| `SILO_OIDC_ISSUER` | `silo.auth.oidc.issuer` | — |
| `SILO_OIDC_JWKS_URL` | `silo.auth.oidc.jwks-url` | — |
| `SILO_OIDC_AUDIENCE` | `silo.auth.oidc.audience` | — |
| `SILO_OIDC_ROLES_CLAIM` | `silo.auth.oidc.roles-claim` | `roles` |
| `SILO_AUDIT_ENABLED` | `silo.audit.enabled` | `false` |
| `SILO_AUDIT_DIR` | `silo.audit.dir` | `/data/audit` |
| `SILO_LOG_SAMPLE_RATE` | logback turbo filter rate | `1` (log all) |
| `SILO_UPDATE_REPO` | _(updater)_ `silo update` source repo | `chrisjenx/silo` |
| `SILO_UPDATE_TOKEN` | _(updater)_ GitHub token for rate limits / private forks | _(unset)_ |

## Users file format

`/etc/silo/users.conf` — the user list **must** be nested under `silo`. A bare
top-level `users = [ ... ]` loads zero users (every authenticated PUT then 401s):

```hocon
silo {
  users = [
    { username = "ci-writer",  password-hash = "$2a$12$...", roles = ["read", "write"] }
    { username = "dev-reader", password-hash = "$2a$12$...", roles = ["read"] }
  ]
}
```

Generate a bcrypt (`$2a$12$…`) hash with the bundled subcommand:

```bash
# interactive: prompts for the password twice (no echo) to catch typos
java -jar silo.jar hash-password

# headless (CI/scripts): read a single line from stdin
echo "$PASSWORD" | java -jar silo.jar hash-password
```

The jar also ships a `silo update` verb for in-place upgrades; see the [operations runbook](operations.md#updating) for details.

## Persistence layout

Under `silo.storage.root`:

```
<root>/
  cas/                          # content-addressed blobs (sharded)
    ab/cd/abcdef...             # blob (filename = full key)
  silo.db                       # SQLite metadata index (WAL mode)
  silo.db-wal                   # SQLite WAL file
  silo.db-shm                   # SQLite shared-memory file
  .silo.lock                    # single-process lockfile
```

**Back up**: `cas/` + `silo.db*` together, taken from a paused (stopped) Silo. See `docs/operations.md`.
