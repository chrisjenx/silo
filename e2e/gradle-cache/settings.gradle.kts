/*
 * Standalone fixture build used by the Gradle build-cache e2e test (#125).
 * Run with the repo's wrapper:  ./gradlew -p e2e/gradle-cache ...
 *
 * The local build cache is disabled so a clean rebuild can only resolve a
 * cached task output from the REMOTE cache (Silo) — proving the round-trip.
 */
rootProject.name = "silo-cache-e2e"

val siloUrl: String = System.getenv("SILO_URL") ?: "http://localhost:8080/cache/"
val siloUser: String? = System.getenv("SILO_CACHE_USER")
val siloPassword: String? = System.getenv("SILO_CACHE_PASSWORD")

buildCache {
    local {
        isEnabled = false
    }
    remote<HttpBuildCache> {
        url = uri(siloUrl)
        isPush = true
        // The e2e talks plain HTTP to a local Silo; in production terminate TLS
        // at a proxy (see docs/tls.md) and drop this.
        isAllowInsecureProtocol = true
        isAllowUntrustedServer = true
        if (siloUser != null && siloPassword != null) {
            credentials {
                username = siloUser
                password = siloPassword
            }
        }
    }
}
