import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("silo.testing-conventions")
    alias(libs.plugins.shadow)
    alias(libs.plugins.cyclonedx)
}

base {
    // The full distributable: silo-<version>-all.jar (server + the update plugin).
    archivesName.set("silo")
}

// Short commit SHA embedded in the jar manifest (mirrors :server).
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
    // Default classifier "all" → silo-<version>-all.jar (what release.yml globs).
    manifest {
        attributes["Main-Class"] = "com.chrisjenx.silo.serverupdate.Main"
        attributes["Implementation-Title"] = "silo"
        attributes["Implementation-Version"] = project.version.toString()
        attributes["Implementation-SHA"] = commitSha
    }
    mergeServiceFiles()
}

dependencies {
    implementation(project(":server"))
    implementation(project(":updater"))

    "unitTestImplementation"(project(":test-fixtures"))
    "integrationTestImplementation"(project(":test-fixtures"))
}
