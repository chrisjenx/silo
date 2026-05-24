---
title: Repository setup
nav_order: 7
---

# Repository Setup

The Silo repo is bootstrapped. This doc records what's applied and gives commands for forks or fresh deployments.

## Applied state

| Setting | State |
|---|---|
| Labels | 26 labels across `type/`, `area/`, `priority/`, flow |
| Milestones | `v0.1 Walking Skeleton`, `v0.2 Production Hardening` |
| Issues | Filed via `.github/scripts/file-issues.sh` |
| Branch protection (`main`) | PR + 1 review + linear history + conversation resolution. CI checks: `build / ubuntu-latest`, `pr-title`. Admins bypass. No force-push, no deletion. |
| Tag protection (`v*`) | Ruleset blocks deletion, non-fast-forward, update |
| Merge settings | Squash + rebase, no merge commits, auto-delete branches |
| Security | Dependabot alerts + auto-fix, secret scanning + push protection |

## Optional, not yet applied

- **Topics + description** — add via `gh repo edit` if not set
- **GitHub Pages** — host the design doc on `chrisjenx.github.io/silo`
- **CODEOWNERS** — file already in `.github/CODEOWNERS`; replace handle on team handoff
- **FUNDING.yml** — file already in `.github/FUNDING.yml`; uncomment lines as accounts are set up
- **Renovate app** — install on the repo so `renovate.json` activates

## Reproduce on a fork

Run these commands once after creating the fork. All idempotent (`--force` on labels, etc.).

### 1. Topics + description

```bash
gh repo edit \
  --description "Silo — fast, free, self-hosted Gradle remote build cache. Drop-in OSS replacement for the EOL Gradle Build Cache Node. Kotlin + Ktor. Fat-jar or Docker. Retro-terminal admin UI. Apache-2.0." \
  --add-topic gradle --add-topic build-cache --add-topic kotlin --add-topic ktor \
  --add-topic self-hosted --add-topic devops --add-topic ci --add-topic caching \
  --add-topic oss --add-topic docker --add-topic jvm --add-topic gradle-build-cache \
  --add-topic developer-tools
```

### 2. Labels

```bash
for t in bug feature chore docs perf-regression security; do
  gh label create "type/$t" --color B60205 --force
done
for a in protocol storage auth admin web observability security perf build ci release docs test; do
  gh label create "area/$a" --color 5319E7 --force
done
gh label create priority/p0 --color B60205 --force
gh label create priority/p1 --color D93F0B --force
gh label create priority/p2 --color FBCA04 --force
gh label create priority/p3 --color C2E0C6 --force
for l in good-first-issue help-wanted needs-design needs-repro blocked wontfix duplicate; do
  gh label create "$l" --color 7057FF --force
done
```

### 3. Milestones + issues

```bash
REPO=$(gh repo view --json nameWithOwner -q .nameWithOwner)
gh api -X POST "repos/$REPO/milestones" -f title="v0.1 Walking Skeleton" -f state=open
gh api -X POST "repos/$REPO/milestones" -f title="v0.2 Production Hardening" -f state=open
bash .github/scripts/file-issues.sh
```

### 4. Merge settings

```bash
gh repo edit \
  --enable-squash-merge \
  --enable-rebase-merge \
  --enable-merge-commit=false \
  --delete-branch-on-merge
```

### 5. Branch protection on main

```bash
REPO=$(gh repo view --json nameWithOwner -q .nameWithOwner)
gh api -X PUT "repos/$REPO/branches/main/protection" --input - <<'JSON'
{
  "required_status_checks": {
    "strict": true,
    "contexts": ["build / ubuntu-latest", "pr-title"]
  },
  "enforce_admins": false,
  "required_pull_request_reviews": {
    "required_approving_review_count": 1,
    "dismiss_stale_reviews": true,
    "require_code_owner_reviews": false
  },
  "required_linear_history": true,
  "allow_force_pushes": false,
  "allow_deletions": false,
  "required_conversation_resolution": true,
  "restrictions": null
}
JSON
```

### 6. Tag protection (ruleset)

The legacy tag-protection endpoint is deprecated. Use a repository ruleset:

```bash
REPO=$(gh repo view --json nameWithOwner -q .nameWithOwner)
gh api -X POST "repos/$REPO/rulesets" --input - <<'JSON'
{
  "name": "Protect v* tags",
  "target": "tag",
  "enforcement": "active",
  "conditions": { "ref_name": { "include": ["refs/tags/v*"], "exclude": [] } },
  "rules": [{"type": "deletion"}, {"type": "non_fast_forward"}, {"type": "update"}]
}
JSON
```

### 7. Security

```bash
REPO=$(gh repo view --json nameWithOwner -q .nameWithOwner)
gh api -X PUT "repos/$REPO/vulnerability-alerts"
gh api -X PUT "repos/$REPO/automated-security-fixes"
gh api -X PATCH "repos/$REPO" --input - <<'JSON'
{
  "security_and_analysis": {
    "secret_scanning": {"status": "enabled"},
    "secret_scanning_push_protection": {"status": "enabled"}
  }
}
JSON
```

### 8. Dependency-Track SBOM publishing (optional)

`release.yml` uploads the CycloneDX SBOM to a [Dependency-Track](https://dependencytrack.org/)
instance on each release. It is a no-op unless these secrets are set, so it stays
dormant on forks and unconfigured repos:

```bash
REPO=$(gh repo view --json nameWithOwner -q .nameWithOwner)
gh secret set DEPENDENCYTRACK_URL --repo "$REPO"      # e.g. https://dtrack.example.com
gh secret set DEPENDENCYTRACK_API_KEY --repo "$REPO"  # DT API key with BOM_UPLOAD + PROJECT_CREATION_UPLOAD
```

The SBOM is uploaded to project `silo`, version = release tag (leading `v` stripped),
with `autoCreate=true` so the project is created on first publish.

### 9. GitHub Pages (optional)

The docs site is built and deployed by the `pages.yml` workflow (Jekyll +
just-the-docs over `docs/`). Set the Pages source to **GitHub Actions** so the
workflow can publish:

```bash
REPO=$(gh repo view --json nameWithOwner -q .nameWithOwner)
gh api -X POST "repos/$REPO/pages" -f build_type=workflow
# If Pages is already enabled with a branch source, switch it:
gh api -X PUT "repos/$REPO/pages" -f build_type=workflow
```

The first deploy then runs on the next push to `main` that touches `docs/**`,
or via **Actions → Deploy docs → Run workflow**.

## Verify

```bash
REPO=$(gh repo view --json nameWithOwner -q .nameWithOwner)
gh api "repos/$REPO/branches/main/protection" --jq '{checks: .required_status_checks.contexts, reviews: .required_pull_request_reviews.required_approving_review_count, linear: .required_linear_history.enabled}'
gh api "repos/$REPO/rulesets" --jq '.[] | "\(.name): \(.target) \(.enforcement)"'
gh api "repos/$REPO" --jq '.security_and_analysis'
gh label list --limit 100 | wc -l
gh issue list --milestone "v0.1 Walking Skeleton" --limit 100 --json number | jq 'length'
```
