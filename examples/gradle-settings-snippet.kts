// Drop this into your project's settings.gradle.kts to point Gradle at a Silo instance.
//
// Trailing slash on the URL is REQUIRED by Gradle.
//
// Credentials are optional if Silo is configured with `silo.auth.anonymous-read = true` and
// you only need to read from the cache. Writers always need credentials.

buildCache {
    remote<HttpBuildCache> {
        url = uri("https://silo.example.com/cache/")
        push = true       // false on developer machines if you only want to read
        // useExpectContinue = true  // optional; helps avoid wasted body upload on 4xx
        credentials {
            username = "ci-writer"
            password = providers.environmentVariable("SILO_CACHE_PASSWORD").orNull
        }
    }
    local {
        enabled = true    // keep the local cache too; remote is layered on top
    }
}

// Optional: enable cache for kotlin compile, etc.
// See https://docs.gradle.org/current/userguide/build_cache.html
