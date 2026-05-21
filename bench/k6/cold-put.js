// 5000 distinct keys, 1 MiB each, 50 VUs.
import http from 'k6/http';
import { check } from 'k6';
import { silo, key, authHeader, bytes } from './lib.js';

const PAYLOAD = bytes(1024 * 1024).buffer; // 1 MiB

export const options = {
    vus: 50,
    iterations: 5000,
    thresholds: {
        http_req_failed: ['rate<0.01'],
        http_req_duration: ['p(99)<100'],
    },
};

export default function () {
    const headers = { 'Content-Type': 'application/octet-stream' };
    const auth = authHeader();
    if (auth) headers['Authorization'] = auth;

    const k = key(1_000_000 + __ITER);
    const res = http.put(`${silo.baseUrl}/cache/${k}`, PAYLOAD, { headers });
    check(res, { 'PUT 200': (r) => r.status === 200 });
}
