plugins {
    id("silo.coverage-conventions")
    id("silo.testing-conventions")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.sigstore.java)

    "unitTestImplementation"(libs.mockk)
}
