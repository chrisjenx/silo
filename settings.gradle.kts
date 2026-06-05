@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "silo"

include(
    ":protocol",
    ":storage",
    ":storage-fs",
    ":metadata-sqlite",
    ":metrics",
    ":updater",
    ":server",
    ":server-update",
    ":test-fixtures",
    ":bench",
)

project(":protocol").projectDir = file("modules/protocol")
project(":storage").projectDir = file("modules/storage")
project(":storage-fs").projectDir = file("modules/storage-fs")
project(":metadata-sqlite").projectDir = file("modules/metadata-sqlite")
project(":metrics").projectDir = file("modules/metrics")
project(":updater").projectDir = file("modules/updater")
project(":server").projectDir = file("modules/server")
project(":server-update").projectDir = file("modules/server-update")
project(":test-fixtures").projectDir = file("modules/test-fixtures")
project(":bench").projectDir = file("modules/bench")
