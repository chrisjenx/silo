---
title: TLS & reverse proxy
nav_order: 6
---

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

### Docker provider (labels)

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
      # IMPORTANT: do NOT enable the buffering middleware for the whole
      # router — it spools the entire body before forwarding and defeats
      # Silo's streaming. Cap size at Silo instead (it 413s via Expect:
      # 100-continue). Only buffer if you must hard-limit at the edge:
      - traefik.http.middlewares.silo-limit.buffering.maxRequestBodyBytes=5368709120
      - traefik.http.middlewares.silo-limit.buffering.memRequestBodyBytes=1048576
      - traefik.http.routers.silo.middlewares=silo-limit
```

### Full static + dynamic config (file provider)

```yaml
# traefik.yml — static config
entryPoints:
  web:
    address: ":80"
    http:
      redirections:
        entryPoint: { to: websecure, scheme: https }
  websecure:
    address: ":443"
    transport:
      respondingTimeouts:
        readTimeout: "120s"     # allow slow, large PUTs
        idleTimeout: "180s"
certificatesResolvers:
  le:
    acme:
      email: ops@example.com
      storage: /acme/acme.json
      tlsChallenge: {}
providers:
  file:
    filename: /etc/traefik/dynamic.yml
```

```yaml
# dynamic.yml — routers/services
http:
  routers:
    silo:
      rule: "Host(`silo.example.com`)"
      entryPoints: [websecure]
      service: silo
      tls:
        certResolver: le
  services:
    silo:
      loadBalancer:
        passHostHeader: true
        servers:
          - url: "http://silo:8080"
        healthCheck:
          path: /health
          interval: "30s"
          timeout: "3s"
```

Traefik streams request and response bodies by default (no `proxy_buffering`
equivalent to disable), so large GET/PUT pass straight through unless you
attach the `buffering` middleware above.

## AWS ALB

Application Load Balancer terminates TLS with an ACM certificate and forwards
plain HTTP to the Silo target group.

- **Listener**: HTTPS:443, ACM cert, forward to the target group. Add an
  HTTP:80 listener that 301-redirects to HTTPS.
- **Target group**: protocol HTTP, port 8080, health check `GET /health`
  (200 expected), `deregistration_delay = 30s`.
- **`idle_timeout = 120s`** on the LB — the 60s default can sever large PUTs
  on slow links.
- ALB does **not** cap request body size, so Silo's `max-entry-bytes` (→ 413)
  is the size guard. ALB also doesn't honour `Expect: 100-continue` end-to-end,
  so oversized PUTs upload fully before Silo rejects them — keep the cap sane.
- Stickiness is **not** required — every request is content-addressed and
  self-routing across targets.

```hcl
# Terraform sketch
resource "aws_lb_target_group" "silo" {
  port     = 8080
  protocol = "HTTP"
  vpc_id   = var.vpc_id
  health_check {
    path                = "/health"
    matcher             = "200"
    interval            = 30
    timeout             = 3
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
  deregistration_delay = 30
}

resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.silo.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.acm_certificate_arn
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.silo.arn
  }
}
```

## Cloudflare Tunnel

`cloudflared` dials out from inside your network, so Silo needs **no inbound
ports** and no public IP. TLS terminates at the Cloudflare edge.

```yaml
# config.yml for cloudflared
tunnel: <TUNNEL-UUID>
credentials-file: /etc/cloudflared/<TUNNEL-UUID>.json
ingress:
  - hostname: silo.example.com
    service: http://silo:8080
    originRequest:
      connectTimeout: 30s
      # No response buffering — stream cache hits straight back.
      noHappyEyeballs: false
  - service: http_status:404
```

```bash
cloudflared tunnel route dns <TUNNEL-UUID> silo.example.com
cloudflared tunnel run <TUNNEL-UUID>
```

Caveats:

- The **free plan caps request bodies at 100 MB**. Large build-cache artifacts
  will fail with `413` from Cloudflare before reaching Silo — use a paid plan or
  a different proxy for big artifacts.
- Cloudflare may buffer/transform; disable **Caching** and any **compression**
  for the Silo hostname so opaque cache bytes pass through untouched.
- Authenticate at Silo (HTTP Basic) and/or with Cloudflare Access in front —
  Silo never trusts `X-Forwarded-*` for auth.

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
