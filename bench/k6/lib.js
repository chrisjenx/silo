// Shared helpers for the silo k6 scripts. Imported via:
//   import { silo } from './lib.js';
// Env vars honoured by every script:
//   BASE_URL    default http://localhost:8080
//   AUTH        optional "user:password" Basic auth pair
//   KEY_PREFIX  default a deterministic per-VU hex seed

export const silo = {
    baseUrl: __ENV.BASE_URL || 'http://localhost:8080',
    auth: __ENV.AUTH || null,
};

/**
 * Build a 64-char lowercase-hex key from a numeric seed. Stable across
 * VUs so cold/hot scripts agree on what's prewarmed.
 */
export function key(seed) {
    const HEX = '0123456789abcdef';
    let s = '';
    let n = seed >>> 0;
    for (let i = 0; i < 64; i += 1) {
        // mulberry32-ish — only needs to be deterministic, not strong.
        n = (n * 1664525 + 1013904223) >>> 0;
        s += HEX[(n >>> (i % 28)) & 0xf];
    }
    return s;
}

/** Builds the Authorization header value when AUTH is set. */
export function authHeader() {
    if (!silo.auth) return null;
    return 'Basic ' + b64(silo.auth);
}

/** k6 ships its own atob/btoa equivalents under k6/encoding, but a
 *  pure-JS variant keeps the scripts dependency-free. */
function b64(s) {
    if (typeof __VU !== 'undefined' && typeof encoding !== 'undefined' && encoding && encoding.b64encode) {
        return encoding.b64encode(s);
    }
    // Fallback: only ASCII passwords get exercised in CI.
    const CHARS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
    let out = '';
    let i = 0;
    while (i < s.length) {
        const a = s.charCodeAt(i++);
        const b = i < s.length ? s.charCodeAt(i++) : 0;
        const c = i < s.length ? s.charCodeAt(i++) : 0;
        out += CHARS[a >> 2];
        out += CHARS[((a & 3) << 4) | (b >> 4)];
        out += i - 1 < s.length + 1 ? CHARS[((b & 15) << 2) | (c >> 6)] : '=';
        out += i - 1 < s.length ? CHARS[c & 63] : '=';
    }
    return out;
}

/** Build a body of N bytes. Single TextEncoder + slice for speed. */
export function bytes(n) {
    return new Uint8Array(n);
}
