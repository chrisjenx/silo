plugins {
    id("silo.ktor-conventions")
    id("silo.testing-conventions")
}

dependencies {
    implementation(project(":protocol"))
    implementation(project(":storage"))
    implementation(project(":storage-fs"))
    implementation(project(":metadata-sqlite"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.default.headers)
    implementation(libs.ktor.server.auth)
    implementation(libs.bcrypt)
    implementation(libs.typesafe.config)

    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)
    implementation(libs.slf4j.api)

    "unitTestImplementation"(project(":test-fixtures"))
    "unitTestImplementation"(libs.ktor.server.test.host)

    "integrationTestImplementation"(project(":test-fixtures"))
    "integrationTestImplementation"(libs.ktor.server.test.host)
}
