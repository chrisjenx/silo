<!--
  PR title: follow Conventional Commits.
  Examples:
    feat(storage): atomic write fallback for cross-FS roots
    fix: 503 on EDQUOT instead of 500
    docs: add Caddyfile example
  Breaking changes: prefix with `!` (feat!: ...).
-->

## What and why

<!-- 2–4 sentences. What changed? What problem did it solve? Link to the issue. -->

Closes #

## How

<!-- Brief design notes. Anything reviewers should know. -->

## Test plan

- [ ] New unit tests cover the change
- [ ] New integration tests cover the change (if HTTP/disk path touched)
- [ ] Existing tests still pass locally (`./gradlew check`)
- [ ] If touching a `CacheStore` impl: contract spec still passes
- [ ] If perf-sensitive: bench numbers included or `bench.yml` will gate

Test evidence (test method names or screenshots):

```

```

## Checklist

- [ ] Conventional Commits title
- [ ] DCO sign-off on every commit (`git commit -s`)
- [ ] Docs updated (README, `docs/*`, or inline) if behavior changed
- [ ] No new dependency on forbidden libs (Spring, Jackson, Koin in core, Hibernate, Exposed for cache data)
- [ ] No body logging, no `!!`, no `runBlocking` outside `main`/tests

## Breaking changes

<!-- If `!` in the title or any breaking change, describe migration here. -->
