plugins {
    id("silo.coverage-conventions")
    id("silo.testing-conventions")
}

dependencies {
    api(libs.micrometer.core)
    api(libs.micrometer.registry.prometheus)
    api(project(":storage"))
    api(project(":storage-fs"))

    "unitTestImplementation"(project(":test-fixtures"))
}
