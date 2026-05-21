# Silo — Repository Setup Checklist

One-time configuration the maintainer applies to the GitHub repo, before or shortly after the
first push. All commands assume `gh` CLI is authenticated and the working dir is the repo.

## 1. Repo description and topics

```bash
gh repo edit \
  --description "Silo — fast, free, self-hosted Gradle remote build cache. Drop-in OSS replacement for the EOL Gradle Build Cache Node. Kotlin + Ktor. Fat-jar or Docker. Retro-terminal admin UI. Apache-2.0." \
  --add-topic gradle \
  --add-topic build-cache \
  --add-topic kotlin \
  --add-topic ktor \
  --add-topic self-hosted \
  --add-topic devops \
  --add-topic ci \
  --add-topic caching \
  --add-topic oss \
  --add-topic docker \
  --add-topic jvm \
  --add-topic gradle-build-cache \
  --add-topic developer-tools
```

## 2. Labels

```bash
# Types
gh label create type/bug             --color B60205
gh label create type/feature         --color 0E8A16
gh label create type/chore           --color C2E0C6
gh label create type/docs            --color 1D76DB
gh label create type/perf-regression --color D93F0B
gh label create type/security        --color B60205

# Areas
for area in protocol storage auth admin web observability security perf build ci release docs test; do
  gh label create "area/$area" --color 5319E7
done

# Priorities
gh label create priority/p0 --color B60205 --description "blocker"
gh label create priority/p1 --color D93F0B --description "next release"
gh label create priority/p2 --color FBCA04 --description "soon"
gh label create priority/p3 --color C2E0C6 --description "eventually"

# Flow
gh label create good-first-issue --color 7057FF
gh label create help-wanted      --color 008672
gh label create needs-design     --color BFD4F2
gh label create needs-repro      --color FBCA04
gh label create blocked          --color B60205
gh label create wontfix          --color C5DEF5
gh label create duplicate        --color CCCCCC
```

## 3. Milestones

```bash
gh api -X POST "repos/$(gh repo view --json nameWithOwner -q .nameWithOwner)/milestones" \
  -f title="v0.1 Walking Skeleton" \
  -f description="Bootable Silo: protocol, storage, auth, eviction, metrics, dashboard."

gh api -X POST "repos/$(gh repo view --json nameWithOwner -q .nameWithOwner)/milestones" \
  -f title="v0.2 Production Hardening" \
  -f description="S3/GCS backends, OIDC, chaos tests, SBOM publish, replication spike."
```

## 4. Branch protection (main)

```bash
REPO=$(gh repo view --json nameWithOwner -q .nameWithOwner)
gh api -X PUT "repos/$REPO/branches/main/protection" \
  -F required_status_checks.strict=true \
  -F required_status_checks.contexts[]="build" \
  -F required_status_checks.contexts[]="test" \
  -F enforce_admins=false \
  -F required_pull_request_reviews.required_approving_review_count=1 \
  -F required_pull_request_reviews.dismiss_stale_reviews=true \
  -F required_linear_history=true \
  -F allow_force_pushes=false \
  -F allow_deletions=false
```

## 5. Tag protection

```bash
gh api -X POST "repos/$REPO/tags/protection" -f pattern="v*"
```

## 6. Default branch + merge settings

- Allow squash merge: **on**
- Allow merge commits: **off**
- Allow rebase merge: **on** (for release-please's preferred workflow)
- Automatically delete head branches: **on**

```bash
gh repo edit \
  --enable-squash-merge \
  --enable-rebase-merge \
  --delete-branch-on-merge=true \
  --enable-merge-commit=false
```

## 7. Security

- Enable Dependabot alerts and security updates (Settings → Security → Enable).
- Enable secret scanning (Settings → Security → Code security).
- Add a private vulnerability reporting policy (Settings → Security → Reporting). Body is in `SECURITY.md`.

```bash
gh api -X PUT "repos/$REPO/vulnerability-alerts"
gh api -X PUT "repos/$REPO/automated-security-fixes"
gh api -X PATCH "repos/$REPO" \
  -F security_and_analysis.secret_scanning.status=enabled \
  -F security_and_analysis.secret_scanning_push_protection.status=enabled
```

## 8. Filing the v0.1 and v0.2 issues

See the bootstrap plan at `~/.claude-work/plans/project-goal-and-archetecture-mossy-naur.md` for
the full issue list. Each issue body should include:

- Acceptance criteria (3–6 bullets)
- Test plan
- References to relevant docs (`docs/limits.md`, `docs/design.md`, etc.)
- Link to the parent milestone

There is a helper script in `.github/scripts/file-issues.sh` (to be added) that wraps `gh issue create` with the standard label set.

## 9. GitHub Pages (optional)

If you want a site at `chrisjenx.github.io/silo`:

```bash
gh api -X POST "repos/$REPO/pages" \
  -f source.branch=main \
  -f source.path=/docs
```

The `docs/` directory already exists with the design doc and other reference material.

## 10. CODEOWNERS, FUNDING, etc.

These live in `.github/`. Update them with real handles before publishing:

- `.github/CODEOWNERS` — replace `<maintainer>` with `@chrisjenx` (or team).
- `.github/FUNDING.yml` — point at a sponsor account once one exists.
