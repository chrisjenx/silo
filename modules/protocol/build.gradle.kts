plugins {
    id("silo.coverage-conventions")
    id("silo.testing-conventions")
}

dependencies {
    implementation(libs.kotlin.stdlib)

    "unitTestImplementation"(project(":test-fixtures"))
    "integrationTestImplementation"(project(":test-fixtures"))
}
