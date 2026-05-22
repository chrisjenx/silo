// 70/30 GET/PUT, 90% hot working set. Regression-gate target for bench.yml.
import http from 'k6/http';
import { check } from 'k6';
import { silo, key, authHeader, bytes } from './lib.js';

const HOT_SIZE = 1_000;
const PAYLOAD = bytes(1024 * 1024).buffer; // 1 MiB

export const options = {
    vus: 100,
    duration: __ENV.DURATION || '5m',
    // Emit p50/p95/p99 into --summary-export so :bench:compareBaseline can read them.
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
    thresholds: {
        http_req_failed: ['rate<0.01'],
        http_req_duration: ['p(99)<150'],
    },
};

export default function () {
    const headers = { 'Content-Type': 'application/octet-stream' };
    const auth = authHeader();
    if (auth) headers['Authorization'] = auth;

    const hot = Math.random() < 0.9;
    const k = key(hot ? Math.floor(Math.random() * HOT_SIZE) : Math.floor(Math.random() * 1_000_000));

    if (Math.random() < 0.7) {
        const res = http.get(`${silo.baseUrl}/cache/${k}`);
        check(res, { 'GET 200|404': (r) => r.status === 200 || r.status === 404 });
    } else {
        const res = http.put(`${silo.baseUrl}/cache/${k}`, PAYLOAD, { headers });
        check(res, { 'PUT 200': (r) => r.status === 200 });
    }
}
