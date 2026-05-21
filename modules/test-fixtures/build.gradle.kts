plugins {
    id("silo.kotlin-conventions")
}

val libs = the<org.gradle.accessors.dm.LibrariesForLibs>()

dependencies {
    api(project(":protocol"))
    api(project(":storage"))
    api(libs.kotest.assertions.core)
    api(libs.kotest.framework.datatest)
    api(libs.kotest.runner.junit5)
    api(libs.kotest.property)
}
