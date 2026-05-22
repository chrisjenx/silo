plugins {
    application
    id("silo.ktor-conventions")
    id("silo.testing-conventions")
    alias(libs.plugins.shadow)
}

application {
    // Shadow reads this to write the fat-jar Main-Class manifest entry.
    // Main.kt is annotated @file:JvmName("Main").
    mainClass.set("com.chrisjenx.silo.server.Main")
}

base {
    // Artifacts are silo-<version>(-all).jar, not server-*. The Dockerfile
    // and release.yml glob for silo-*-all.jar.
    archivesName.set("silo")
}

dependencies {
    implementation(project(":protocol"))
    implementation(project(":storage"))
    implementation(project(":storage-fs"))
    implementation(project(":metadata-sqlite"))
    implementation(project(":metrics"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.default.headers)
    implementation(libs.ktor.server.metrics.micrometer)
    implementation(libs.ktor.server.auth)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.bcrypt)
    implementation(libs.typesafe.config)

    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)
    implementation(libs.slf4j.api)

    "unitTestImplementation"(project(":test-fixtures"))
    "unitTestImplementation"(libs.ktor.server.test.host)

    "integrationTestImplementation"(project(":test-fixtures"))
    "integrationTestImplementation"(libs.ktor.server.test.host)
}
