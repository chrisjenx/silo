// 100 prewarmed keys, 10k GETs at conc 200. Run cold-put.js (or smoke
// then a setup PUT loop) first to populate the working set.
import http from 'k6/http';
import { check } from 'k6';
import { silo, key } from './lib.js';

const PRE = 100;
const PAYLOAD_SIZE = 1024 * 1024; // 1 MiB

export const options = {
    vus: 200,
    iterations: 10_000,
    thresholds: {
        http_req_failed: ['rate<0.001'],
        http_req_duration: ['p(99)<50'],
    },
};

export function setup() {
    // Best-effort seed — script may also run after cold-put.js.
    for (let i = 0; i < PRE; i += 1) {
        http.put(`${silo.baseUrl}/cache/${key(i)}`, new ArrayBuffer(PAYLOAD_SIZE), {
            headers: { 'Content-Type': 'application/octet-stream' },
        });
    }
}

export default function () {
    const k = key(__ITER % PRE);
    const res = http.get(`${silo.baseUrl}/cache/${k}`);
    check(res, {
        'GET 200': (r) => r.status === 200,
        'GET size': (r) => r.body && r.body.length === PAYLOAD_SIZE,
    });
}
