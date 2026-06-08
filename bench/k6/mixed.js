// 70/30 GET/PUT, 90% hot working set. Regression-gate target for bench.yml.
import http from 'k6/http';
import { check } from 'k6';
import { silo, key, authHeader, bytes } from './lib.js';

// A 404 is a cache miss, not an error in the Gradle build-cache protocol —
// don't let it inflate http_req_failed. Real errors (401/5xx) still count.
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 204 }, 404));

const HOT_SIZE = 1000;
// Bound the cold working set so the on-disk footprint is duration-independent.
// 1 MiB payloads × this many distinct keys is the worst-case storage Silo
// holds; the bench writes to a tmpfs (/dev/shm, ~8 GB on CI). Eviction IS wired,
// but its sweeper runs on a ~60 s cadence and can't keep pace with this
// high-rate write burst — and we don't want eviction I/O perturbing the latency
// we're measuring — so the bench bounds the cold set rather than leaning on the
// sweeper. An unbounded cold space would fill the tmpfs and Silo would
// (correctly) return 503 on ENOSPC, failing the gate for an environmental
// reason, not a real regression. 5000 × 1 MiB ≈ 5 GB, which fits one server's
// fresh tmpfs (run-k6.sh reclaims each jar's data dir on exit so the base and
// current runs never co-reside).
const COLD_SIZE = 5000;
const PAYLOAD = bytes(1024 * 1024).buffer; // 1 MiB

export const options = {
    vus: 100,
    duration: __ENV.DURATION || '5m',
    // Emit p50/p95/p99 into --summary-export so :bench:compareBaseline can read them.
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
    // Only real failures (auth/5xx) hard-fail the run. Absolute latency is
    // environment-dependent on shared CI runners, so the regression gate
    // (:bench:compareBaseline vs the baseline) owns latency, not a fixed number.
    thresholds: {
        http_req_failed: ['rate<0.01'],
    },
};

export default function () {
    const headers = { 'Content-Type': 'application/octet-stream' };
    const auth = authHeader();
    if (auth) headers['Authorization'] = auth;

    const hot = Math.random() < 0.9;
    const k = key(hot ? Math.floor(Math.random() * HOT_SIZE) : Math.floor(Math.random() * COLD_SIZE));

    if (Math.random() < 0.7) {
        const res = http.get(`${silo.baseUrl}/cache/${k}`);
        check(res, { 'GET 200|404': (r) => r.status === 200 || r.status === 404 });
    } else {
        const res = http.put(`${silo.baseUrl}/cache/${k}`, PAYLOAD, { headers });
        check(res, { 'PUT 200': (r) => r.status === 200 });
    }
}
