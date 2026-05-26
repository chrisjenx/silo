# Changelog

## [0.1.3](https://github.com/chrisjenx/silo/compare/v0.1.2...v0.1.3) (2026-05-26)


### Bug Fixes

* wire documented env vars + reconcile docs with shipped code ([#141](https://github.com/chrisjenx/silo/issues/141)) ([4d6675e](https://github.com/chrisjenx/silo/commit/4d6675e5f5a977a18480b97f66213dd9db8b1340))

## [0.1.2](https://github.com/chrisjenx/silo/compare/v0.1.1...v0.1.2) (2026-05-26)


### Bug Fixes

* iterate cosign sign over tags line-by-line ([#139](https://github.com/chrisjenx/silo/issues/139)) ([b12826b](https://github.com/chrisjenx/silo/commit/b12826b86eb8e8beb9f4ee9c371f8c86a0e23da8))

## [0.1.1](https://github.com/chrisjenx/silo/compare/v0.1.0...v0.1.1) (2026-05-26)


### Bug Fixes

* unblock v0.1.0 release pipeline (cosign pin + changelog lint) ([#137](https://github.com/chrisjenx/silo/issues/137)) ([328959b](https://github.com/chrisjenx/silo/commit/328959b0abd862ab323d280817885df68769943e))

## 0.1.0 (2026-05-26)


### Features

* add :bench module with kotlinx-benchmark JMH ([#36](https://github.com/chrisjenx/silo/issues/36)) ([#103](https://github.com/chrisjenx/silo/issues/103)) ([b374d32](https://github.com/chrisjenx/silo/commit/b374d32aeba312349f4a7ea3c798f1669d0d167b))
* add append-only JSONL audit log for admin mutations ([#57](https://github.com/chrisjenx/silo/issues/57)) ([#120](https://github.com/chrisjenx/silo/issues/120)) ([28e6cd4](https://github.com/chrisjenx/silo/commit/28e6cd4f3d07b8edb9b4b0d03391ade87f873624))
* add code-quality gates — ktlint, detekt, kover ([#1](https://github.com/chrisjenx/silo/issues/1)b) ([#70](https://github.com/chrisjenx/silo/issues/70)) ([f1b64d3](https://github.com/chrisjenx/silo/commit/f1b64d3d19b5c77a824a29aabb5014adfeca18e6))
* add hash-password CLI subcommand and fix users-file docs ([#136](https://github.com/chrisjenx/silo/issues/136)) ([8f3fa72](https://github.com/chrisjenx/silo/commit/8f3fa723abdcb74d86f4a9555b9e501ffa9c7894))
* add SSE /api/stream/stats live stats stream ([#56](https://github.com/chrisjenx/silo/issues/56)) ([#119](https://github.com/chrisjenx/silo/issues/119)) ([60ab979](https://github.com/chrisjenx/silo/commit/60ab97943d1943efb8d0be69f09d976ba9999d85))
* admin GET /api/config (redacted) ([#15](https://github.com/chrisjenx/silo/issues/15)) ([#96](https://github.com/chrisjenx/silo/issues/96)) ([08cbeca](https://github.com/chrisjenx/silo/commit/08cbeca4af7358546dafb01a591a0c6eb1a1c7f3))
* admin GET /api/stats — aggregated cache stats ([#13](https://github.com/chrisjenx/silo/issues/13)) ([#94](https://github.com/chrisjenx/silo/issues/94)) ([eef1214](https://github.com/chrisjenx/silo/commit/eef1214af54f852a3b52b7ccb6df746c509ff79f))
* admin GET /api/storage — disk + cache state ([#14](https://github.com/chrisjenx/silo/issues/14)) ([#95](https://github.com/chrisjenx/silo/issues/95)) ([5e0025e](https://github.com/chrisjenx/silo/commit/5e0025e96dca01a909a904d3511c94795545fd3f))
* admin SPA holding-page + /admin static-resources route ([#16](https://github.com/chrisjenx/silo/issues/16)) ([#97](https://github.com/chrisjenx/silo/issues/97)) ([5d96dec](https://github.com/chrisjenx/silo/commit/5d96dec116f68ecc3e40278f6819f1ebc239dc6f))
* basic auth with role split + bcrypt verification cache ([#10](https://github.com/chrisjenx/silo/issues/10)) ([#86](https://github.com/chrisjenx/silo/issues/86)) ([ae9420d](https://github.com/chrisjenx/silo/commit/ae9420d0cbb68780edb623a3871d23caffd29d84))
* bench/jmh scaffold with placeholder cachekeyvalidationbench ([#21](https://github.com/chrisjenx/silo/issues/21)) ([#102](https://github.com/chrisjenx/silo/issues/102)) ([5adc10a](https://github.com/chrisjenx/silo/commit/5adc10af2b6533ef028fa33c1f4660c64ccd3998))
* cache-store interface + contract-spec scenarios ([#3](https://github.com/chrisjenx/silo/issues/3)) ([#75](https://github.com/chrisjenx/silo/issues/75)) ([c4d3129](https://github.com/chrisjenx/silo/commit/c4d31298a0229151994fbdbf8a3ba33fc4934a39))
* cross-FS rename fallback wired through an injectable AtomicMover ([#4](https://github.com/chrisjenx/silo/issues/4)d) ([#80](https://github.com/chrisjenx/silo/issues/80)) ([050cb90](https://github.com/chrisjenx/silo/commit/050cb901dfdf309eebd43bd28fd72ce3ea022a60))
* design tokens — phosphor / amber / paper themes ([#17](https://github.com/chrisjenx/silo/issues/17)) ([#98](https://github.com/chrisjenx/silo/issues/98)) ([ee9f963](https://github.com/chrisjenx/silo/commit/ee9f96331098c38d2313ea3a3a0eb08b80efc267))
* embed version in fat jar + silo --version flag ([#43](https://github.com/chrisjenx/silo/issues/43)) ([#112](https://github.com/chrisjenx/silo/issues/112)) ([00c0f49](https://github.com/chrisjenx/silo/commit/00c0f498d527fd0ea67241b7e6a139bc3f79dba2))
* enforce Apache-2.0 header on every .kt file via spotless ([#2](https://github.com/chrisjenx/silo/issues/2)) ([#71](https://github.com/chrisjenx/silo/issues/71)) ([d07bd2a](https://github.com/chrisjenx/silo/commit/d07bd2aa4248d61a6c320aab16c7649f33f8a986))
* enforce storage caps — wire eviction engine + reserved-free guard ([#135](https://github.com/chrisjenx/silo/issues/135)) ([9d12fdc](https://github.com/chrisjenx/silo/commit/9d12fdce0091825038023ccd883acf263ec5a7dd))
* ENOENT self-heal on GET — purge stale metadata row ([#11](https://github.com/chrisjenx/silo/issues/11)c) ([#90](https://github.com/chrisjenx/silo/issues/90)) ([2c065d3](https://github.com/chrisjenx/silo/commit/2c065d322b8b7931fdb28bdac209b98b7ca857b1))
* ENOSPC / EDQUOT / reserved-free handling on PUT ([#11](https://github.com/chrisjenx/silo/issues/11)d) ([#91](https://github.com/chrisjenx/silo/issues/91)) ([76916de](https://github.com/chrisjenx/silo/commit/76916de90844d8f71b7ebddf9632e55f4f24e6bf))
* eviction engine — LRU byte/entry caps with budgeted sweep ([#11](https://github.com/chrisjenx/silo/issues/11)) ([#87](https://github.com/chrisjenx/silo/issues/87)) ([1a9450c](https://github.com/chrisjenx/silo/commit/1a9450c8df7a8108e72041ee7693398fd3a5c814))
* filesystem cache store with sharded layout + atomic rename ([#4](https://github.com/chrisjenx/silo/issues/4)) ([#76](https://github.com/chrisjenx/silo/issues/76)) ([43151b7](https://github.com/chrisjenx/silo/commit/43151b722e767e66ad4d95e4c677073d835c35a6))
* GET /cache/{key} route ([#6](https://github.com/chrisjenx/silo/issues/6)) ([#82](https://github.com/chrisjenx/silo/issues/82)) ([ea30c10](https://github.com/chrisjenx/silo/commit/ea30c10828ec2266838cd58be1c9b02258babbfe))
* head /cache/{key} route ([#8](https://github.com/chrisjenx/silo/issues/8)) ([#84](https://github.com/chrisjenx/silo/issues/84)) ([2191259](https://github.com/chrisjenx/silo/commit/2191259038294207577267d4678fde223d00b60c))
* k6 smoke + load suite ([#20](https://github.com/chrisjenx/silo/issues/20)) ([#101](https://github.com/chrisjenx/silo/issues/101)) ([d54bbe6](https://github.com/chrisjenx/silo/commit/d54bbe63d319cb59aa55251b5a8d391800328e9d))
* ktor app skeleton with /health and /ready probes ([#5](https://github.com/chrisjenx/silo/issues/5)) ([#81](https://github.com/chrisjenx/silo/issues/81)) ([b940189](https://github.com/chrisjenx/silo/commit/b940189dde6a703c23319dc3046f4e2e80ec076b))
* multi-arch docker hardening + ci build verification ([#37](https://github.com/chrisjenx/silo/issues/37)) ([#104](https://github.com/chrisjenx/silo/issues/104)) ([5f911b1](https://github.com/chrisjenx/silo/commit/5f911b1f75445e9a3c39d2e168fc37fcddc1eec9))
* NFS root detection — refuse to start ([#4](https://github.com/chrisjenx/silo/issues/4)c) ([#79](https://github.com/chrisjenx/silo/issues/79)) ([dd6145b](https://github.com/chrisjenx/silo/commit/dd6145b69ce4fe015849a494e8b89b290ed37441))
* OIDC / Bearer-token auth (resource-server mode) ([#54](https://github.com/chrisjenx/silo/issues/54)) ([#118](https://github.com/chrisjenx/silo/issues/118)) ([8b28486](https://github.com/chrisjenx/silo/commit/8b2848600014e5ea4c7e7d6c9b71f65383c6943c))
* periodic SQLite WAL checkpoint + VACUUM maintenance ([#63](https://github.com/chrisjenx/silo/issues/63)) ([#127](https://github.com/chrisjenx/silo/issues/127)) ([fab65b6](https://github.com/chrisjenx/silo/commit/fab65b6f86a42bcddfb4b24d83b3e6a12232dee8))
* playable dashboard demo, configured-caps panel, and favicon ([#131](https://github.com/chrisjenx/silo/issues/131)) ([5eb1857](https://github.com/chrisjenx/silo/commit/5eb1857eac80116e8f45ddb61d6ba09e923b734b))
* pre-commit hook + Gradle installGitHooks task ([#2](https://github.com/chrisjenx/silo/issues/2)d) ([#74](https://github.com/chrisjenx/silo/issues/74)) ([ce2e513](https://github.com/chrisjenx/silo/commit/ce2e5130e416a7aa8c1c79afcdabc5a51c1c19e8))
* prometheus metrics endpoint ([#12](https://github.com/chrisjenx/silo/issues/12)) ([#93](https://github.com/chrisjenx/silo/issues/93)) ([27097da](https://github.com/chrisjenx/silo/commit/27097dacc5af0ed2e6529081a6178e15bbde61c1))
* publish CycloneDX SBOM to Dependency-Track on release ([#61](https://github.com/chrisjenx/silo/issues/61)) ([#126](https://github.com/chrisjenx/silo/issues/126)) ([9a47176](https://github.com/chrisjenx/silo/commit/9a47176dd289ba6d960890434d9441de72108e25))
* put /cache/{key} route ([#7](https://github.com/chrisjenx/silo/issues/7)) ([#83](https://github.com/chrisjenx/silo/issues/83)) ([203ddd9](https://github.com/chrisjenx/silo/commit/203ddd98d32c9f10c703e03f72057af4d696d509))
* reconciliation sweep + POST /api/storage/reconcile ([#11](https://github.com/chrisjenx/silo/issues/11)b) ([#89](https://github.com/chrisjenx/silo/issues/89)) ([89f0fd3](https://github.com/chrisjenx/silo/commit/89f0fd30446b91a9b0a72ed71fd95b8a3549ff1b))
* reject oversize PUTs before reading the body ([#11](https://github.com/chrisjenx/silo/issues/11)e) ([#92](https://github.com/chrisjenx/silo/issues/92)) ([72e4e23](https://github.com/chrisjenx/silo/commit/72e4e23534634266c8575a15c4aa6300f1dd23fe))
* scaffold Gradle multi-module project ([#1](https://github.com/chrisjenx/silo/issues/1)) ([#68](https://github.com/chrisjenx/silo/issues/68)) ([877aea8](https://github.com/chrisjenx/silo/commit/877aea8931a92e7170c705f9c1d4742543898a30))
* sqlite WAL metadata index with batched last_access flush ([#4](https://github.com/chrisjenx/silo/issues/4)a) ([#77](https://github.com/chrisjenx/silo/issues/77)) ([ca6cb91](https://github.com/chrisjenx/silo/commit/ca6cb914c90a28070b2e6e878ddc68ba90add40a))
* startup recovery scan for orphan tmp files ([#60](https://github.com/chrisjenx/silo/issues/60)) ([#123](https://github.com/chrisjenx/silo/issues/123)) ([2aec49e](https://github.com/chrisjenx/silo/commit/2aec49e0c3b934fe9f8896e0a4a1f39281580625))
* static dashboard page wired to /api/stats + /api/storage ([#18](https://github.com/chrisjenx/silo/issues/18)) ([#99](https://github.com/chrisjenx/silo/issues/99)) ([35b44f7](https://github.com/chrisjenx/silo/commit/35b44f7759aab75cc9e5f4d28878816f36f6f584))
* storage-root single-process lockfile via FileChannel.tryLock ([#4](https://github.com/chrisjenx/silo/issues/4)b) ([#78](https://github.com/chrisjenx/silo/issues/78)) ([1a1072e](https://github.com/chrisjenx/silo/commit/1a1072e17028e46a8787cece7b522022b2a2f5d4))
* structured-log polish — request id MDC + access-log sampling ([#58](https://github.com/chrisjenx/silo/issues/58)) ([#121](https://github.com/chrisjenx/silo/issues/121)) ([3734593](https://github.com/chrisjenx/silo/commit/3734593f9b85d9bfa86acb9311c7146f17474561))
* themed 404 + about pages ([#19](https://github.com/chrisjenx/silo/issues/19)) ([#100](https://github.com/chrisjenx/silo/issues/100)) ([396cb06](https://github.com/chrisjenx/silo/commit/396cb06a23e0016b7613fa9c1da6c5bc671b6fc6))
* TTL sweeper — age-first eviction before cap-based LRU ([#11](https://github.com/chrisjenx/silo/issues/11)a) ([#88](https://github.com/chrisjenx/silo/issues/88)) ([9975147](https://github.com/chrisjenx/silo/commit/9975147e59515c8415c7d362e36f59c64a3ad76a))
* verify-sha256-on-read tamper detection + chaos test ([#50](https://github.com/chrisjenx/silo/issues/50)) ([#116](https://github.com/chrisjenx/silo/issues/116)) ([b31dbfc](https://github.com/chrisjenx/silo/commit/b31dbfc786b05bfffc130e0d6e419ec58b6b0977))
* wire kotest, source-set split, and :test-fixtures skeleton ([#1](https://github.com/chrisjenx/silo/issues/1)a) ([#69](https://github.com/chrisjenx/silo/issues/69)) ([3778ae0](https://github.com/chrisjenx/silo/commit/3778ae0a3be6c662073f67446aa2a6453372c656))


### Bug Fixes

* bound the bench cold key space so tmpfs can't fill on the 5m run ([#133](https://github.com/chrisjenx/silo/issues/133)) ([1a59ec8](https://github.com/chrisjenx/silo/commit/1a59ec85bc349a049b23f5e39a1a8516580d0a74))
* self-enable GitHub Pages and link docs site from the README ([#130](https://github.com/chrisjenx/silo/issues/130)) ([d4971e6](https://github.com/chrisjenx/silo/commit/d4971e6004ece33d5f73accdb770de0718fa8f3c))


### Miscellaneous Chores

* force first release to 0.1.0 via Release-As footer ([#114](https://github.com/chrisjenx/silo/issues/114)) ([f78a295](https://github.com/chrisjenx/silo/commit/f78a295dcf3b1d49b2dbc0d62204f918988c6142))
