# silo · k6 load suite

Standalone JavaScript scripts. Run from the repo root with the
binary in `$PATH`:

```bash
k6 run bench/k6/smoke.js
k6 run -e BASE_URL=https://silo.dev bench/k6/mixed.js
k6 run -e AUTH='user:secret' bench/k6/cold-put.js
```

Common env vars: `BASE_URL` (default `http://localhost:8080`),
`AUTH` (`user:password` for HTTP Basic), `DURATION` (`mixed.js`).

The `bench.yml` workflow runs `mixed.js` for 5 minutes nightly and
treats a > 10% p99 latency regression versus the cached baseline as
a failure. See `docs/operations.md` for the full perf budget.
