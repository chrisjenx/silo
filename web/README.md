# Silo Admin SPA (web/)

Kobweb (Compose-HTML, static export) project that builds the
`/admin/*` SPA the dashboard tracks.

**Status:** scaffold only. The Kobweb build is intentionally deferred
to a follow-up issue: it brings in Compose-HTML + Kotlin/JS toolchains
which are heavy to wire and don't gate any of the v0.1 server work.
When it lands, this project is a separate Gradle composite — the
`:server` module never depends on Kobweb.

The current placeholder lives at
`modules/server/src/main/resources/static/admin/index.html` so the
server already serves a real (terminal-look) holding page at `/admin/`
when the fat jar boots.

## Future layout

```
web/
├── settings.gradle.kts        # composite project: includes :site
└── site/
    ├── build.gradle.kts       # kobweb { app { ... } }
    └── src/jsMain/kotlin/com/chrisjenx/silo/web/...
```

A root `:server` Gradle task (see `modules/server/build.gradle.kts`,
TODO: `assembleAdminSpa`) will:

1. Run `./gradlew :site:kobwebExport` from inside `web/`.
2. Copy the static export tree into
   `modules/server/src/main/resources/static/admin/`.
3. The fat-jar build picks it up automatically — the route already
   serves the directory.

For now, edit the static `index.html` directly when you want the
holding-page copy to change.
