---
title: Limits
nav_order: 5
---

# Silo — OS, Filesystem, and Ktor Limits

What Silo expects from its host, and what to set if you're tuning for scale.

## Recommended setup at a glance

| Item | Recommendation |
|---|---|
| OS | Linux (any modern distro) or macOS for development |
| Filesystem | **xfs** (production) or **ext4** with `dir_index` (default since 2008) |
| Mount options | `defaults,noatime` is fine — Silo tracks access in SQLite |
| `ulimit -n` | ≥ 65,536 |
| Disk free | At least `silo.storage.reserved-free-bytes` (default 5 GB) over the configured cap |
| Inode free | At least `silo.storage.reserved-free-inodes` (default 100,000) |
| Network | Behind a reverse proxy for TLS (see `docs/tls.md`) |

## Filesystem

### ext4

- Without `dir_index` (htree), directory lookup is O(n) and degrades sharply above ~10K entries.
- With `dir_index` (enabled by default at `mkfs` since e2fsprogs 1.41), lookups are O(log n) and scale to millions per directory — but the index pages still need to be cached for that to be fast.
- Silo shards `cas/{ab}/{cd}/{key}` to 65,536 leaf directories. At 1M entries that's ~15 per leaf. The total file count is what matters for inode budget.
- Verify `dir_index` is on: `dumpe2fs -h /dev/sdX | grep features` should list `dir_index`.

### xfs

- Recommended for production. Allocation groups give parallel I/O; large-file performance is excellent; fragmentation is low on write-once content-addressed workloads.
- No tuning needed for Silo's usage pattern.

### APFS (macOS)

- Case-insensitive by default. Hex cache keys do not collide in practice, but if you're running test suites that hand-craft uppercase/lowercase variants you'll want a case-sensitive volume (`diskutil apfs addVolume ... -caseSensitive`).
- Otherwise fine for development.

### NTFS (Windows)

- `MAX_PATH = 260` characters default. Silo's sharded layout keeps paths short; the storage root path must leave headroom for `cas/ab/cd/<128-char-key>` (≈ 138 chars).
- Long-path opt-in via `\\?\` prefix or system policy is on the roadmap for v0.2 if a Windows user needs deep roots.

### overlayfs (Docker default writable layer)

- `rename(2)` is atomic within the upper layer. Silo writes the temp file in the **same shard dir** as the final, so renames never cross layers.
- If you bind-mount `/data` to a host volume (recommended), the data lives on the host filesystem and overlayfs is bypassed entirely.

### NFS — **not supported**

- `fsync` semantics on NFS are weak; `rename(2)` is not atomic over RPC; locking is unreliable.
- Silo refuses to start if the storage root is on `nfs` / `nfs3` / `nfs4`, detected by walking `/proc/self/mountinfo` (Linux) and matching the deepest mount point containing the storage root.
- On macOS and Windows the detection is a no-op + WARN — there is no `/proc` equivalent, so we cannot reliably tell. Run with care.
- Override (not recommended): `silo.storage.allow-unsupported-fs = true` downgrades the abort to a WARN. Use only if you are absolutely sure your filesystem is not actually NFS (e.g. a containerised test harness that lies to `mountinfo`).
- Need network-shared storage? Wait for the S3 backend (v0.2) or run Silo locally on each node.

### tmpfs / ramdisk

- Allowed but flagged with a startup WARN. Use only for ephemeral CI scratch — there is no durability and the host will OOM as the cache fills.

## Disk / inode exhaustion

Silo monitors free space and inode count continuously. On any write that would breach a reserve, it returns HTTP `503` and emits `silo_storage_errors_total{kind="reserved_free_bytes|reserved_free_inodes"}`.

If the kernel returns `ENOSPC` (no blocks) or `EDQUOT` (quota) anyway, Silo:

1. Unreserves the in-flight allocation
2. Logs ERROR with key prefix and bytes
3. Returns `503` to the client
4. Increments `silo_storage_errors_total{kind="enospc|edquot"}`

Cross-filesystem renames (storage root spans two mounts) trigger `AtomicMoveNotSupportedException`. Silo catches it, falls back to copy+delete with a WARN log, and tells you to move the root onto a single filesystem.

## Process / OS

- **File descriptor limit.** Default `ulimit -n 1024` is inadequate. Set `LimitNOFILE=65536` in systemd, or `--ulimit nofile=65536` on Docker. Silo logs the limit at startup and WARNs if it's < 4096.
- **Single process per data root.** Enforced by `FileChannel.tryLock()` on `.silo.lock` plus SQLite's own WAL lock. Refuse-to-start with `ERR: storage root locked by PID N` on conflict.
- **TIME_WAIT exhaustion.** Netty enables `SO_REUSEADDR` by default. For very high-churn CI clusters set `net.ipv4.tcp_tw_reuse=1` on the host.

## Ktor / Netty (3.5)

| Setting | Value | Why |
|---|---|---|
| `SO_BACKLOG` | 512 | Bursty CI traffic overruns the default 128 |
| `requestReadTimeout` | 60s | Slowloris mitigation |
| `Dispatchers.IO.limitedParallelism` | 64 | Storage ops gated; semaphore also limits to `silo.server.max-concurrent-disk-ops` |
| PUT body handling | `call.receiveChannel()` | Never `receive<ByteArray>` — would buffer the whole body |
| `Expect: 100-continue` | Honored out of the box | Auth/size pre-check responds `100` or rejects without reading the body |
| HTTP/1.1 keep-alive | Unlimited per connection | Ktor default; CI clients reuse heavily |

## Filesystem watchers — **not used**

Silo deliberately does not watch the storage tree with `WatchService` / inotify:

- inotify drops events silently under load (above ~8K events/sec on default tuning).
- macOS `FSEvents` and Linux inotify behave differently; cross-platform parity is fragile.
- The reconciliation sweep (default hourly) plus on-read ENOENT fallback gives stronger self-healing for far less complexity.

If you need faster drift detection, lower `silo.reconcile.interval-minutes`. Drift is also detected lazily on every GET.

## Performance budget

CI (`bench.yml`) gates these on every nightly run. Regression > 10% fails the build.

- GET hit p99 < 50 ms for 1 MB on commodity SSD
- PUT p99 < 100 ms for 1 MB
- RSS < 200 MB idle, < 500 MB under 100 rps mixed
- Sustained 500 MB PUT/GET must hold streaming throughput without heap growth
