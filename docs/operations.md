---
title: Operations
nav_order: 5
---

# Silo — Operations Runbook

How to run, back up, monitor, and recover a Silo deployment.

## First boot

1. Decide your storage root. Production: a dedicated mount, xfs or ext4 with `dir_index`, **not** NFS. See `docs/limits.md`.
2. Pre-create `users.conf` with bcrypt hashes (or leave anonymous-read on if your cache is on a private network).

   ```bash
   java -jar silo.jar hash-password
   ```

3. Mount the storage volume and start:

   ```bash
   docker run -d --name silo \
     -p 8080:8080 \
     -v /srv/silo-data:/data \
     -v /etc/silo/users.conf:/etc/silo/users.conf:ro \
     ghcr.io/chrisjenx/silo:latest
   ```

4. Probe health:

   ```bash
   curl -fsS http://localhost:8080/health   # liveness — process is up
   curl -fsS http://localhost:8080/ready    # readiness — storage writable, SQLite open
   ```

5. Round-trip:

   ```bash
   # store
   echo "hello" | curl -sf --upload-file - http://localhost:8080/cache/0123456789abcdef0123456789abcdef01234567
   # fetch
   curl -fsS http://localhost:8080/cache/0123456789abcdef0123456789abcdef01234567
   ```

## Backup

The data root is everything. `cas/` (blobs) and `silo.db*` (metadata) must be backed up **together** and consistently.

### Option 1 — paused snapshot (simplest, no concurrent writes)

```bash
docker stop silo
tar -C /srv/silo-data -czf silo-$(date +%Y%m%d).tgz cas silo.db silo.db-wal silo.db-shm
docker start silo
```

### Option 2 — filesystem snapshot (zfs/btrfs/lvm)

Take a filesystem-level snapshot of the volume while Silo is still running. SQLite WAL guarantees the snapshot is recoverable. Stream the snapshot to your backup target.

### Option 3 — read-only Silo + rsync

```bash
# Toggle storage to read-only via env override and restart
docker restart -e SILO_READONLY=true silo
rsync -a --delete /srv/silo-data/ user@backup:/backups/silo/
docker restart silo
```

### What **not** to do

- Don't `rsync` while Silo is writing — you can capture a half-written blob or an out-of-sync SQLite WAL.
- Don't back up `cas/` without `silo.db` — entries lose metadata and the next reconcile will re-discover them but lose `inserted_at` precision.

## Restore

1. Stop Silo.
2. Untar / rsync the backup into the data root.
3. Verify the lockfile is gone (`rm -f /srv/silo-data/.silo.lock` if needed — only if you're sure no other Silo is running).
4. Start Silo. On startup it scans for orphan `.tmp.*` files (>10 min old) and removes them. The first reconcile reconciles SQLite against the disk.

## Logging

Logs are JSON (Logback + logstash-encoder) on stdout — ship them straight to your aggregator.

- **Request id**: every request is tagged with a `requestId` (the `X-Request-ID` header if the client/proxy sends one, else a generated UUID). It is echoed in the `X-Request-ID` response header and put in MDC, so it propagates across coroutine suspension points and appears in the JSON log line for that request. Correlate a client failure to a server log by its request id.
- **Sampling**: on very busy nodes the per-request access line dominates log volume. Set `SILO_LOG_SAMPLE_RATE=N` to keep only 1 of every N INFO access lines (default `1` = keep all). WARN and above are never sampled, so error signal is preserved. Body bytes are never logged at any level.

## Monitoring

- `/metrics` → Prometheus.
- `/health` → 200 once the JVM is up.
- `/ready` → 200 once SQLite is open and the storage root is writable.
- `/api/stats` → one-shot JSON stats snapshot (entryCount, bytesStored, hits, misses, puts, evictions, hitRate).
- `/api/stream/stats` → Server-Sent Events; emits the same JSON snapshot once per second as `data:` frames. The admin dashboard subscribes for live tile updates. Honors the same read-auth posture as `/api/stats` (anonymous when `anonymous-read = true`, else READ role required). Example: `curl -N http://localhost:8080/api/stream/stats`.

### Audit log

With `silo.audit.enabled = true`, every admin-API mutation (e.g. `POST /api/storage/reconcile`) appends one JSON line to `<silo.audit.dir>/audit-<UTC-date>.jsonl`. Each entry records `timestamp`, `actor` (the authenticated username, or `anonymous`), `action`, `outcome`, and action-specific `details`. Files rotate at the UTC day boundary — point your log shipper at the directory glob and let it tail the current day's file. The log is append-only; back it up or ship it off-box if you need tamper-evidence.

Key metrics to watch:

| Metric | Alert when |
|---|---|
| `silo_storage_errors_total{kind="enospc"}` | rate > 0 |
| `silo_storage_errors_total{kind="reserved_free_bytes"}` | rate > 0 (acceptance dropped due to disk threshold) |
| `silo_drift_detected_total` | rate spike — investigate external interference |
| `silo_recovery_orphans_cleaned_total` | non-zero after a boot — the node crashed mid-PUT previously; expected, but a steady rate hints at frequent unclean shutdowns |
| `silo_corruption_detected_total` | non-zero — investigate immediately |
| `silo_request_duration_seconds{quantile="0.99"}` | > performance budget |
| `silo_store_size_bytes` | trending toward `max-bytes` faster than eviction runs |
| `silo_eviction_queue_depth` | non-zero for sustained periods → eviction can't keep up |

## Tuning

### Cache too small

Symptoms: high `silo_cache_misses_total` rate, low hit ratio.

- Raise `silo.storage.max-bytes` and `silo.storage.max-entries`. Make sure the host has room.
- Raise `silo.eviction.max-age-days` if hot artifacts are aging out.
- Inspect `/api/storage` for top-N largest entries — sometimes one bloated artifact dominates.

### Slow PUTs

- Verify `fsync-on-write` and `fsync-dir-on-rename` are tolerable on your storage. SSD + battery-backed: yes. Spinning rust + sync writes: prepare for pain.
- Check `Dispatchers.IO.limitedParallelism` and `silo.server.max-concurrent-disk-ops` — raise on fast NVMe.
- Inspect proxy buffering settings. `nginx` without `proxy_request_buffering off` will appear slow.

### SQLite growth

- A background task runs `PRAGMA wal_checkpoint(TRUNCATE)` every `silo.sqlite.checkpoint-interval-seconds` (default 300s) to keep `silo.db-wal` from growing without bound under sustained writes, and `VACUUM` every `silo.sqlite.vacuum-interval-seconds` (default 24h) to reclaim pages after large evictions.
- Both run on the metadata writer lock, so they serialize with writes but never block reads. On a large index a `VACUUM` rewrites the whole DB file — if it stalls writers noticeably, lengthen the vacuum interval rather than disabling checkpoints.

### Slow GETs

- Hot keys should fit in the page cache. If your working set exceeds RAM and you're paging from disk, GET p99 will rise. Add RAM or split the cache.
- `verify-sha256-on-read = true` costs ~1 GB/s CPU. Turn it off unless you have a reason to suspect corruption.

## External interference

If admins, scripts, or container orchestrators are deleting files under `cas/` out of band:

- The first GET on a deleted blob self-heals (SQLite row is purged, 404 returned). `silo_drift_detected_total{kind="missing_blob"}` increments.
- The hourly reconcile sweep cleans up the rest. Force a sweep with:

  ```bash
  curl -fsS -X POST -u admin:... http://localhost:8080/api/storage/reconcile
  ```

- If your eviction needs are stronger than Silo's TTL+LRU, do **not** `rm` blobs directly. Use the admin API delete endpoint (`POST /api/storage/delete/{key}`) so the metadata index stays in sync.

## Disaster recovery

Lost the data volume entirely?

- There's nothing to recover from a build cache — it's content-addressed and rebuildable. CI will re-populate it on the next run.
- If you had a backup, restore it (see above). The first reconcile may take a few minutes on multi-million-entry caches; the cache is serving requests throughout.

## Two Silo instances on one data root

Don't. The `.silo.lock` file enforces this — the second instance will refuse to start. If you want HA, run two instances with different data roots in front of a load balancer; cache will diverge slightly but neither will corrupt. True multi-node replication is on the roadmap for v0.3.
