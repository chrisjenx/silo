import http from 'k6/http';
import { check } from 'k6';
import { silo, key, authHeader, bytes } from './lib.js';

export const options = {
    vus: 1,
    iterations: 5,
    thresholds: { http_req_failed: ['rate<0.01'] },
};

export default function () {
    const k = key(__ITER + 1);
    const headers = { 'Content-Type': 'application/octet-stream' };
    const auth = authHeader();
    if (auth) headers['Authorization'] = auth;

    const put = http.put(`${silo.baseUrl}/cache/${k}`, bytes(1024).buffer, { headers });
    check(put, { 'PUT 200': (r) => r.status === 200 });

    const get = http.get(`${silo.baseUrl}/cache/${k}`);
    check(get, {
        'GET 200': (r) => r.status === 200,
        'GET size': (r) => r.body && r.body.length === 1024,
    });
}
