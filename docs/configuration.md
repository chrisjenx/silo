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
    shutdownGracePeriod = 10000

    # Netty engine
    netty.tcpKeepAlive = true
    netty.shareWorkGroup = true
    netty.requestReadTimeoutSeconds = 60
  }
  application {
    modules = [ com.chrisjenx.silo.server.ApplicationKt.module ]
  }
}

silo {
  server {
    # Hard cap on request body size, applied with Expect: 100-continue early reject.
    request-size-limit-bytes = 524288000           # 500 MB
    request-size-limit-bytes = ${?SILO_REQUEST_SIZE_LIMIT}

    # Slowloris idle timeout (ms) — propagates to Netty ReadTimeoutHandler.
    idle-timeout-ms = 60000

    # Bound concurrent storage operations on Dispatchers.IO.
    max-concurrent-disk-ops = 64
  }

  storage {
    backend = "filesystem"
    backend = ${?SILO_STORAGE_BACKEND}             # filesystem | s3 (v0.2)

    filesystem {
      root = "/var/lib/silo"
      root = ${?SILO_STORAGE_ROOT}

      # Total cache size cap (bytes). LRU evicts oldest by last_access until under cap.
      max-bytes = 107374182400                     # 100 GB
      max-bytes = ${?SILO_MAX_BYTES}

      # Total entry cap. Inode-exhaustion guard for small-artifact workloads.
      max-entries = 1000000
      max-entries = ${?SILO_MAX_ENTRIES}

      # Per-entry cap. PUT > limit returns 413 (early via Expect: 100-continue).
      max-entry-bytes = 2147483648                 # 2 GB
      max-entry-bytes = ${?SILO_MAX_ENTRY_BYTES}

      # Stop accepting PUTs (503) below these free-space thresholds.
      reserved-free-bytes = 5368709120             # 5 GB
      reserved-free-inodes = 100000

      # Shard depth: 2 means cas/{ab}/{cd}/{key} (65,536 leaf dirs).
      shard-depth = 2

      # Durability knobs. Disable only if storage is on battery-backed SSD.
      fsync-on-write = true
      fsync-dir-on-rename = true
    }

    # Opt-in content-hash verification on every read. Costs ~1 GB/s CPU.
    verify-sha256-on-read = false
    verify-sha256-on-read = ${?SILO_VERIFY_SHA256}

    s3 {
      # v0.2 — not yet implemented.
      bucket   = ${?SILO_S3_BUCKET}
      region   = ${?SILO_S3_REGION}
      prefix   = "silo-cache/"
      endpoint = ${?SILO_S3_ENDPOINT}              # for MinIO / R2 / etc.
    }
  }

  metadata {
    # Path to SQLite metadata DB. Defaults to <storage root>/silo.db.
    path = ${?SILO_METADATA_PATH}

    # Batched UPDATE flush interval for last_access bumps.
    last-access-flush-interval-seconds = 60
  }

  auth {
    # When true, GET/HEAD work without credentials. PUT always requires write role.
    anonymous-read = true
    anonymous-read = ${?SILO_ANONYMOUS_READ}

    # bcrypt user list. See `docs/operations.md` for hash generation.
    users-file = "/etc/silo/users.conf"
    users-file = ${?SILO_USERS_FILE}

    # In-memory verification cache TTL (avoids per-request bcrypt cost).
    verify-cache-ttl-seconds = 300
  }

  eviction {
    policy = "lru"
    target-utilization = 0.90

    # Background sweeper cadence.
    sweep-interval-seconds = 60

    # TTL: drop entries untouched for this many days. Set to 0 to disable.
    max-age-days = 30
    max-age-days = ${?SILO_MAX_AGE_DAYS}

    # Throttle to avoid I/O storms on big sweeps.
    max-deletes-per-cycle = 1000
  }

  reconcile {
    # How often to walk cas/** and reconcile against SQLite.
    interval-minutes = 60

    # Lazy walk batch size; yields between batches.
    batch-size = 5000
  }

  metrics {
    enabled = true
    bind-prometheus = "/metrics"
    common-tags {
      env = ${?SILO_ENV}
      instance = ${?HOSTNAME}
    }
  }

  admin {
    enabled = true
    base-path = "/admin"
    # CORS origins allowed for /api/*. The /cache/* protocol routes do not emit CORS.
    cors-origins = [ "http://localhost:3000" ]
  }
}
```

## Env-var quick reference

| Env var | HOCON path | Default |
|---|---|---|
| `SILO_PORT` | `ktor.deployment.port` | `8080` |
| `SILO_STORAGE_BACKEND` | `silo.storage.backend` | `filesystem` |
| `SILO_STORAGE_ROOT` | `silo.storage.filesystem.root` | `/var/lib/silo` |
| `SILO_MAX_BYTES` | `silo.storage.filesystem.max-bytes` | `100 GB` |
| `SILO_MAX_ENTRIES` | `silo.storage.filesystem.max-entries` | `1,000,000` |
| `SILO_MAX_ENTRY_BYTES` | `silo.storage.filesystem.max-entry-bytes` | `2 GB` |
| `SILO_MAX_AGE_DAYS` | `silo.eviction.max-age-days` | `30` |
| `SILO_REQUEST_SIZE_LIMIT` | `silo.server.request-size-limit-bytes` | `500 MB` |
| `SILO_USERS_FILE` | `silo.auth.users-file` | `/etc/silo/users.conf` |
| `SILO_ANONYMOUS_READ` | `silo.auth.anonymous-read` | `true` |
| `SILO_VERIFY_SHA256` | `silo.storage.verify-sha256-on-read` | `false` |
| `SILO_S3_BUCKET` | `silo.storage.s3.bucket` | — |
| `SILO_S3_REGION` | `silo.storage.s3.region` | — |
| `SILO_S3_ENDPOINT` | `silo.storage.s3.endpoint` | — |

## Users file format

`/etc/silo/users.conf`:

```hocon
users = [
  { username = "ci-writer",  password-hash = "$2a$12$...", roles = ["read", "write"] }
  { username = "dev-reader", password-hash = "$2a$12$...", roles = ["read"] }
]
```

Generate a bcrypt hash with:

```bash
java -jar silo.jar hash-password
# prompts for password (not echoed) → prints "$2a$12$..."
```

## Persistence layout

Under `silo.storage.filesystem.root`:

```
<root>/
  cas/                          # content-addressed blobs (sharded)
    ab/cd/abcdef...             # blob (filename = full key)
  silo.db                       # SQLite metadata index (WAL mode)
  silo.db-wal                   # SQLite WAL file
  silo.db-shm                   # SQLite shared-memory file
  .silo.lock                    # single-process lockfile
  _silo/
    size-checkpoint.json        # optional in-memory mirror snapshot
```

**Back up**: `cas/` + `silo.db` together, taken from a paused or read-only Silo. See `docs/operations.md`.
