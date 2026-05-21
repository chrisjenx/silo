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
    ":server",
    ":test-fixtures",
)

project(":protocol").projectDir = file("modules/protocol")
project(":storage").projectDir = file("modules/storage")
project(":storage-fs").projectDir = file("modules/storage-fs")
project(":metadata-sqlite").projectDir = file("modules/metadata-sqlite")
project(":server").projectDir = file("modules/server")
project(":test-fixtures").projectDir = file("modules/test-fixtures")
