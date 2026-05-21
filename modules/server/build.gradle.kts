plugins {
    id("silo.ktor-conventions")
    id("silo.testing-conventions")
}

dependencies {
    implementation(project(":protocol"))
    implementation(project(":storage"))
}
