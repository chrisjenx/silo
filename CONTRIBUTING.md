# Contributing to Silo

Thanks for considering a contribution. Silo is small and opinionated; the guidelines below keep it that way.

## Code of conduct

By participating you agree to the [Code of Conduct](CODE_OF_CONDUCT.md).

## How to contribute

1. **Find an issue** — start with [`good-first-issue`](https://github.com/chrisjenx/silo/labels/good-first-issue) or comment on any issue to claim it.
2. **Discuss first** — for non-trivial changes open an issue or draft PR before writing a lot of code. Saves both of us time.
3. **Fork, branch, code, test** — see `Development workflow` below.
4. **Open a PR** against `main`. The PR template walks you through the checklist.

## Development environment

- JDK 21+ (Temurin recommended). `sdkman` makes this easy.
- Docker (for integration + Compose examples).
- `gh` CLI (for filing/reviewing issues and PRs).
- Optional: `k6` for load tests, `cosign` for verifying release signatures.

Bootstrap:

```bash
git clone https://github.com/chrisjenx/silo
cd silo
./gradlew installGitHooks   # ktlint + detekt pre-commit
./gradlew build             # full local build + tests
```

## Development workflow

- **Branch from `main`**. Name `<area>/<short-description>` (e.g. `storage/atomic-rename-fallback`).
- **TDD by default.** Red → green → refactor. New behavior needs a failing test first. The exceptions are `application.conf` wiring and `main()`.
- **kotest** (`BehaviorSpec` for HTTP, `StringSpec` for pure functions).
- **Single concern per PR.** If you find yourself touching three unrelated areas, split.
- **Run `./gradlew check` before pushing.** That runs unit + integration tests, ktlint, detekt, Kover thresholds.

## Git hooks

After your first clone, run:

```bash
./gradlew installGitHooks
```

This points `core.hooksPath` at `.githooks/`. The pre-commit hook fires
only when at least one `.kt`/`.kts` file is staged. It then auto-formats
the whole tree (`spotlessApply` + `ktlintFormat`) and re-stages any
fixes, and runs `detekt` across the project; detekt violations block
the commit. Skip with `git commit --no-verify` (not recommended).

## Conventional Commits

PR titles must follow [Conventional Commits](https://www.conventionalcommits.org/):

```
feat: stream PUT bodies via ByteReadChannel
fix(storage): handle EDQUOT as 503
docs: add NFS unsupported note
chore(deps): bump ktor to 3.5.1
perf(eviction): batch last_access updates
```

Allowed types: `feat`, `fix`, `perf`, `refactor`, `docs`, `test`, `chore`, `build`, `ci`.

The PR title subject (the bit after `type:` or `type(scope):`) must be lowercase
and 1–72 characters. Move detail to the PR description.

Breaking changes: append `!` (`feat!: rename CacheStore.put → CacheStore.store`) and explain in the body.

`release-please` reads commits to generate the changelog. **Never edit `CHANGELOG.md` by hand.**

A [commitlint workflow](.github/workflows/commitlint.yml) validates the PR title
against the rules above on every pull request.

## Code style

- **ktlint** + **detekt** enforce style. Run `./gradlew ktlintFormat` to auto-fix.
- No `!!`. detekt blocks it.
- No `runBlocking` outside `main` and tests.
- All disk/network I/O inside `withContext(Dispatchers.IO)`.
- Never log request/response bodies — log key prefix, size, duration, outcome only.
- Forbidden dependencies: Spring, Jackson, Koin (in core), Hibernate, Exposed (for cache data).

## Tests

- New code needs unit tests. Network/disk paths need integration tests under `src/integrationTest/kotlin`.
- Storage backends must extend `CacheStoreContractSpec` and all tests must pass before merging.
- Coverage gates: 80% line on `:protocol` and `:storage` modules.
- Performance-sensitive changes need a benchmark in `:bench` and may be gated by `bench.yml`.

## PR review SLO

- First reviewer comment within **3 business days**.
- We prefer many small PRs to one large PR.
- Reviewers won't bikeshed style — `ktlint` does that — but will push back on architecture and tests.

## Release process

Maintainers only. See the `release.yml` workflow.

## Questions

Open a [GitHub Discussion](https://github.com/chrisjenx/silo/discussions). For private questions email `silo@chrisjenkins.dev`.
