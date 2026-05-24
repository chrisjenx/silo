#!/usr/bin/env bash
#
# End-to-end Gradle build-cache test against a running Silo (#125).
#
# Drives the fixture build under e2e/gradle-cache with the REAL Gradle HTTP
# build-cache client:
#   1. cold build  -> :compileJava executes and is PUSHED to Silo
#   2. clean rebuild -> :compileJava resolves FROM-CACHE (remote, local off)
# and cross-checks Silo's /api/stats (puts after cold, hits after rebuild).
#
# Assumes Silo is already running. Env:
#   SILO_URL            cache endpoint (default http://localhost:8080/cache/)
#   SILO_STATS_URL      stats endpoint (default http://localhost:8080/api/stats)
#   SILO_CACHE_USER     write-role username (optional if anonymous write)
#   SILO_CACHE_PASSWORD write-role password
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
fixture="e2e/gradle-cache"
gradle="${GRADLE_CMD:-$repo_root/gradlew}"

export SILO_URL="${SILO_URL:-http://localhost:8080/cache/}"
stats_url="${SILO_STATS_URL:-http://localhost:8080/api/stats}"

stat_field() { # $1 = json, $2 = field
  python3 -c "import json,sys; print(json.loads(sys.argv[1])[sys.argv[2]])" "$1" "$2"
}

cd "$repo_root"

echo "::group::cold build"
cold_out="$("$gradle" -p "$fixture" --build-cache --console=plain clean compileJava 2>&1)"
echo "$cold_out"
echo "::endgroup::"

puts_after_cold="$(stat_field "$(curl -fsS "$stats_url")" puts)"
echo "puts after cold build: $puts_after_cold"
if [ "$puts_after_cold" -lt 1 ]; then
  echo "FAIL: expected Silo puts >= 1 after the cold build, got $puts_after_cold" >&2
  exit 1
fi

echo "::group::clean rebuild"
rebuild_out="$("$gradle" -p "$fixture" --build-cache --console=plain clean compileJava 2>&1)"
echo "$rebuild_out"
echo "::endgroup::"

if ! grep -q "Task :compileJava FROM-CACHE" <<<"$rebuild_out"; then
  echo "FAIL: :compileJava was not served FROM-CACHE on rebuild" >&2
  exit 1
fi

hits_after_rebuild="$(stat_field "$(curl -fsS "$stats_url")" hits)"
echo "hits after rebuild: $hits_after_rebuild"
if [ "$hits_after_rebuild" -lt 1 ]; then
  echo "FAIL: expected Silo hits >= 1 after the rebuild, got $hits_after_rebuild" >&2
  exit 1
fi

echo "PASS: cold build PUSHED to Silo and rebuild resolved FROM-CACHE."
