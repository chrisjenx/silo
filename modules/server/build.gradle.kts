import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("silo.ktor-conventions")
    id("silo.testing-conventions")
    alias(libs.plugins.shadow)
    alias(libs.plugins.cyclonedx)
}

base {
    // Artifacts are silo-<version>(-all).jar, not server-*. The Dockerfile
    // and release.yml glob for silo-*-all.jar.
    archivesName.set("silo")
}

tasks.named<ShadowJar>("shadowJar") {
    // Set Main-Class directly rather than via the `application` plugin:
    // Shadow 8.3.x + application wires startShadowScripts against the
    // removed `mainClassName` property and breaks `assemble` on Gradle 9.
    // Main.kt is annotated @file:JvmName("Main").
    manifest {
        attributes["Main-Class"] = "com.chrisjenx.silo.server.Main"
    }
    // Merge META-INF/services so ServiceLoader (BackendFactory), Netty,
    // and SLF4J provider descriptors survive the fat-jar shading.
    mergeServiceFiles()
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
