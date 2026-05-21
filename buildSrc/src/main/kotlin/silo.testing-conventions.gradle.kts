plugins {
    id("silo.kotlin-conventions")
}

val libs = the<org.gradle.accessors.dm.LibrariesForLibs>()

val unitTest by sourceSets.creating {
    compileClasspath += sourceSets["main"].output
    runtimeClasspath += sourceSets["main"].output
}

val integrationTest by sourceSets.creating {
    compileClasspath += sourceSets["main"].output
    runtimeClasspath += sourceSets["main"].output
}

configurations {
    named(unitTest.implementationConfigurationName) { extendsFrom(getByName("implementation")) }
    named(unitTest.runtimeOnlyConfigurationName) { extendsFrom(getByName("runtimeOnly")) }
    named(integrationTest.implementationConfigurationName) { extendsFrom(getByName("implementation")) }
    named(integrationTest.runtimeOnlyConfigurationName) { extendsFrom(getByName("runtimeOnly")) }
}

dependencies {
    add(unitTest.implementationConfigurationName, libs.kotest.runner.junit5)
    add(unitTest.implementationConfigurationName, libs.kotest.assertions.core)
    add(unitTest.implementationConfigurationName, libs.kotest.property)
    add(unitTest.implementationConfigurationName, libs.kotest.framework.engine)

    add(integrationTest.implementationConfigurationName, libs.kotest.runner.junit5)
    add(integrationTest.implementationConfigurationName, libs.kotest.assertions.core)
    add(integrationTest.implementationConfigurationName, libs.kotest.property)
    add(integrationTest.implementationConfigurationName, libs.kotest.framework.engine)
}

val unitTestTask = tasks.register<Test>("unitTest") {
    description = "Runs unit tests under src/unitTest/kotlin."
    group = JavaBasePlugin.VERIFICATION_GROUP
    testClassesDirs = unitTest.output.classesDirs
    classpath = unitTest.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.named("test"))
}

val integrationTestTask = tasks.register<Test>("integrationTest") {
    description = "Runs integration tests under src/integrationTest/kotlin."
    group = JavaBasePlugin.VERIFICATION_GROUP
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(unitTestTask)
}

tasks.named("check") {
    dependsOn(unitTestTask, integrationTestTask)
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
        showExceptions = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    systemProperty("kotest.framework.classpath.scanning.config.disable", "true")
    systemProperty("kotest.framework.classpath.scanning.autoscan.disable", "true")
}
