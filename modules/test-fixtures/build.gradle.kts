plugins {
    id("silo.kotlin-conventions")
    id("silo.testing-conventions")
}

dependencies {
    api(libs.kotest.assertions.core)
    api(libs.kotest.framework.engine)
    api(libs.kotest.runner.junit5)
    api(libs.kotest.property)
    api(libs.kotlinx.coroutines.core)
}
