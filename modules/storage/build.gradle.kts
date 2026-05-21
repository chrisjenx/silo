plugins {
    id("silo.coverage-conventions")
    id("silo.testing-conventions")
}

dependencies {
    api(project(":protocol"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)

    "unitTestImplementation"(project(":test-fixtures"))
    "integrationTestImplementation"(project(":test-fixtures"))
}
