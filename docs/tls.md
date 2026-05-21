# Silo — TLS and Reverse Proxy

Silo speaks plain HTTP on its listen port by default. **Terminate TLS at a reverse proxy.**
Inline HTTPS is supported but not the recommended path.

## Why reverse-proxy by default

- Battle-tested implementations (Caddy, nginx, Traefik, ALB) handle certificate rotation, OCSP stapling, HSTS, and weak-cipher pruning correctly.
- TLS in front of the cache means the cache process does not need root for port 443 and does not need access to private-key material.
- A reverse proxy can also add rate limiting, IP allowlists, and request-size caps as a first line of defense.

## Caddy (easiest)

Automatic Let's Encrypt:

```
silo.example.com {
    reverse_proxy silo:8080
    encode zstd gzip

    # Stop body buffering — let Silo stream
    request_body {
        max_size 5GB
    }
}
```

That's it. Caddy fetches and renews certificates on its own. See `examples/Caddyfile` for a
docker-compose-friendly variant.

## nginx

```nginx
upstream silo_upstream {
    server silo:8080;
    keepalive 32;
}

server {
    listen 443 ssl http2;
    server_name silo.example.com;

    ssl_certificate     /etc/letsencrypt/live/silo.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/silo.example.com/privkey.pem;

    # Strong defaults
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers HIGH:!aNULL:!MD5;
    ssl_prefer_server_ciphers on;
    add_header Strict-Transport-Security "max-age=63072000" always;

    # Let large PUTs through
    client_max_body_size 5g;
    client_body_buffer_size 16k;
    proxy_request_buffering off;     # stream PUTs straight through
    proxy_buffering off;             # stream GETs straight back

    # Keepalive to upstream
    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;

    location / {
        proxy_pass http://silo_upstream;
    }
}
```

**Key flags**: `proxy_request_buffering off` and `proxy_buffering off` — without these, nginx
will spool the full body to disk before forwarding, which negates Silo's streaming and hits
your nginx worker disk hard.

## Traefik

```yaml
# docker-compose.yml fragment
services:
  silo:
    image: ghcr.io/chrisjenx/silo:latest
    labels:
      - traefik.enable=true
      - traefik.http.routers.silo.rule=Host(`silo.example.com`)
      - traefik.http.routers.silo.entrypoints=websecure
      - traefik.http.routers.silo.tls.certresolver=le
      - traefik.http.services.silo.loadbalancer.server.port=8080
      - traefik.http.middlewares.silo-buffer.buffering.maxRequestBodyBytes=5368709120
      - traefik.http.routers.silo.middlewares=silo-buffer
```

## AWS ALB

- Set `idle_timeout = 120s` (default 60s can break large PUTs on slow links).
- Target group health check: HTTP `GET /health`.
- Listener: HTTPS:443 with an ACM certificate.
- Stickiness is **not** required — every request is content-addressed and self-routing.

## Inline TLS (opt-in)

If you must run TLS in-process:

```hocon
ktor.deployment {
  sslPort = 8443
  sslKeyStore = /etc/silo/keystore.p12
  sslKeyStorePassword = ${SILO_KEYSTORE_PASSWORD}
  sslPrivateKeyPassword = ${SILO_KEY_PASSWORD}
  sslKeyAlias = silo
}
```

You are now responsible for rotating the certificate. Silo does **not** integrate ACME.

## What Silo expects from the proxy

- `X-Forwarded-Proto: https` is forwarded if you log it — but Silo does not need it for routing.
- `X-Forwarded-For` is **not** trusted for auth decisions. Silo authenticates by HTTP Basic only.
- Don't strip `Expect: 100-continue` — Silo uses it to early-reject oversized or unauthorized PUTs before the body is uploaded.
- Don't transcode the body or set `Content-Encoding`. The body is opaque to the proxy.
