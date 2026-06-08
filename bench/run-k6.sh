#!/usr/bin/env bash
# Boot a Silo jar, run the mixed k6 workload three times, and write the
# median-of-3 http_req_duration percentiles (med/p95/p99) to <out-json>.
#
# Usage: bench/run-k6.sh <jar> <out-json> [duration]
#
# Used by .github/workflows/bench.yml to benchmark two jars (base vs PR) on the
# SAME runner back-to-back, so the regression gate compares like-for-like and is
# immune to cross-run GitHub-runner variance. Each invocation boots on a fresh
# /dev/shm data root, waits for /health, runs k6 x3, and tears the server down.
set -euo pipefail

JAR="$1"
OUT="$2"
DURATION="${3:-1m}"

DATA="$(mktemp -d -p /dev/shm)"
USERS="$DATA/ci-users.conf"
# Throwaway WRITE user so the mixed workload's PUTs are authorized.
# bcrypt hash of "silo-ci-secret"; an ephemeral CI credential.
cat > "$USERS" <<'USERS_CONF'
silo {
  users = [
    {
      username = "ci"
      password-hash = "$2a$10$y3XGw7R720reVhD91l/yYuJbMcIBd4NsFhYPErAUy91jC3P14Si3W"
      roles = ["read", "write"]
    }
  ]
}
USERS_CONF

# Disable the free-space/inode reserve: the bench runs on a small /dev/shm tmpfs
# and the default 5 GB / 100k reserve would reject the workload's PUTs.
# Production keeps the documented defaults.
SILO_PORT=8080 SILO_STORAGE_ROOT="$DATA" \
  java -Dsilo.auth.users-file="$USERS" \
    -Dsilo.storage.reserved-free-bytes=0 \
    -Dsilo.storage.reserved-free-inodes=0 \
    -jar "$JAR" &
SILO_PID=$!
# On exit, reap the server (so the next invocation can rebind 8080) AND delete
# this run's /dev/shm data dir. The cleanup is load-bearing: bench.yml invokes
# this script twice back-to-back (base jar, then current jar), and mixed.js's
# cold working set is ~5 GB (COLD_SIZE × 1 MiB). Without the rm, base's ~5 GB
# would still occupy the tmpfs while current writes another ~5 GB, blowing past
# the ~8 GB /dev/shm and triggering ENOSPC → 503s → a false gate failure.
# Reclaiming $DATA keeps the two runs from ever co-residing.
trap 'kill "$SILO_PID" 2>/dev/null || true; wait "$SILO_PID" 2>/dev/null || true; rm -rf "$DATA"' EXIT

# Wait for readiness (up to ~30s).
for _ in $(seq 1 30); do
  if curl -fsS http://127.0.0.1:8080/health >/dev/null 2>&1; then break; fi
  sleep 1
done
curl -fsS http://127.0.0.1:8080/health

for i in 1 2 3; do
  k6 run --summary-export="$DATA/run${i}.json" \
    -e BASE_URL=http://127.0.0.1:8080 \
    -e AUTH='ci:silo-ci-secret' \
    -e DURATION="$DURATION" \
    bench/k6/mixed.js
done

# Per-percentile median of the three runs so one noisy run doesn't skew the gate.
median() {
  jq -s --arg k "$1" '[.[].metrics.http_req_duration[$k]] | sort | .[1]' \
    "$DATA/run1.json" "$DATA/run2.json" "$DATA/run3.json"
}
printf '{"metrics":{"http_req_duration":{"med":%s,"p(95)":%s,"p(99)":%s}}}\n' \
  "$(median med)" "$(median 'p(95)')" "$(median 'p(99)')" > "$OUT"
echo "median-of-3 → $(cat "$OUT")"
