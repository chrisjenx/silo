plugins {
    id("silo.coverage-conventions")
    id("silo.testing-conventions")
}

dependencies {
    implementation(libs.kotlin.stdlib)
    api(libs.kotlinx.io.core)

    "unitTestImplementation"(project(":test-fixtures"))
    "integrationTestImplementation"(project(":test-fixtures"))
}
