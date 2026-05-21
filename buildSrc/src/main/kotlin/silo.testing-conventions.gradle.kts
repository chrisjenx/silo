plugins {
    id("silo.kotlin-conventions")
}

val libs = the<org.gradle.accessors.dm.LibrariesForLibs>()

dependencies {
    "testImplementation"(libs.kotest.runner.junit5)
    "testImplementation"(libs.kotest.assertions.core)
    "testImplementation"(libs.kotest.property)
    "testImplementation"(libs.kotest.framework.datatest)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
    systemProperty("kotest.framework.classpath.scanning.config.disable", "true")
    systemProperty("kotest.framework.classpath.scanning.autoscan.disable", "true")
}
