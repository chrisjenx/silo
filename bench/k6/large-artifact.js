// 50 PUTs of 500 MiB. Designed to be run with `-Xmx256m` on the
// server JVM — the streaming path should keep RSS comfortably under
// the heap cap even with bodies orders of magnitude larger.
import http from 'k6/http';
import { check } from 'k6';
import { silo, key, authHeader, bytes } from './lib.js';

const PAYLOAD = bytes(500 * 1024 * 1024).buffer; // 500 MiB

export const options = {
    vus: 1,
    iterations: 50,
    thresholds: {
        http_req_failed: ['rate<0.01'],
        http_req_duration: ['p(99)<60000'],
    },
};

export default function () {
    const headers = { 'Content-Type': 'application/octet-stream' };
    const auth = authHeader();
    if (auth) headers['Authorization'] = auth;

    const k = key(2000000 + __ITER);
    const res = http.put(`${silo.baseUrl}/cache/${k}`, PAYLOAD, { headers });
    check(res, { 'PUT 200': (r) => r.status === 200 });
}
