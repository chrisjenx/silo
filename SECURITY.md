# Security Policy

## Supported versions

Silo is pre-1.0. Only the **latest release** receives security fixes. Older versions are not supported.

| Version | Supported |
|---|---|
| latest `v0.x` | yes |
| earlier `v0.x` | no — upgrade |

Once Silo reaches 1.0, the latest minor of the current major and the latest minor of the previous major will be supported.

## Reporting a vulnerability

**Please do not file public GitHub issues for security reports.**

Use GitHub's private vulnerability reporting:
<https://github.com/chrisjenx/silo/security/advisories/new>

Or email `security@chrisjenkins.dev` (PGP key on request).

Include:

- A description of the issue
- Reproduction steps or a proof-of-concept
- Affected version(s)
- Impact assessment (what an attacker can do)
- Your suggested fix, if any

## What to expect

- Acknowledgement within **3 business days**.
- Triage and initial assessment within **7 business days**.
- A coordinated fix targeting a release within **90 days** of confirmation. For severe issues we will release out-of-band.
- Public disclosure happens **after** a patched release is available. We credit reporters in release notes unless you ask us not to.

## Scope

In scope:

- Path traversal, RCE, auth bypass, denial of service, cache poisoning, secret leakage in the server or admin UI.
- Vulnerabilities in shipped Docker images.
- Vulnerabilities in direct dependencies that Silo can mitigate at the server layer.

Out of scope:

- Issues in third-party services Silo integrates with — report to them.
- Theoretical attacks requiring access to the host that would compromise the host anyway.
- Missing security headers on the admin UI when accessed without a reverse proxy that adds them (use a reverse proxy).
- Self-XSS or vulnerabilities that require the victim to paste arbitrary content into devtools.

## Cryptographic releases

All release artifacts (fat jar, Docker images) are signed with **cosign** keyless signing via GitHub OIDC. Verify with:

```bash
cosign verify ghcr.io/chrisjenx/silo:vX.Y.Z \
  --certificate-identity-regexp 'https://github.com/chrisjenx/silo/' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
```

The fat jar's SHA-256 checksum is attached to each GitHub Release.

SBOMs (CycloneDX format) are attached to every release.
