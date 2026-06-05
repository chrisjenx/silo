import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("silo.ktor-conventions")
    id("silo.testing-conventions")
    alias(libs.plugins.shadow)
}

base {
    // The slim distributable (Docker): silo-<version>-slim.jar — no :updater/sigstore.
    archivesName.set("silo")
}

// Short commit SHA embedded in the jar manifest. CI passes -Psilo.commit;
// locally we shell out to git; if neither is available, "unknown".
val commitSha: String =
    (project.findProperty("silo.commit") as String?)?.takeIf { it.isNotBlank() }
        ?: runCatching {
            providers.exec {
                commandLine("git", "rev-parse", "--short", "HEAD")
                isIgnoreExitValue = true
            }.standardOutput.asText.get().trim()
        }.getOrNull()?.takeIf { it.isNotEmpty() }
        ?: "unknown"

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("slim")
    manifest {
        attributes["Main-Class"] = "com.chrisjenx.silo.server.Main"
        attributes["Implementation-Title"] = "silo"
        attributes["Implementation-Version"] = project.version.toString()
        attributes["Implementation-SHA"] = commitSha
    }
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
    implementation(libs.ktor.server.call.id)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.bcrypt)
    implementation(libs.nimbus.jose.jwt)
    implementation(libs.typesafe.config)

    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)
    implementation(libs.slf4j.api)

    "unitTestImplementation"(project(":test-fixtures"))
    "unitTestImplementation"(libs.ktor.server.test.host)

    "integrationTestImplementation"(project(":test-fixtures"))
    "integrationTestImplementation"(libs.ktor.server.test.host)
}
