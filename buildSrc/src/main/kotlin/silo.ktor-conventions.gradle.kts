plugins {
    id("silo.kotlin-conventions")
}

val libs = the<org.gradle.accessors.dm.LibrariesForLibs>()

dependencies {
    "implementation"(libs.kotlinx.coroutines.core)
    "implementation"(libs.ktor.server.core)
    "implementation"(libs.ktor.server.netty)
}
