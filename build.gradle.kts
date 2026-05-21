plugins {
    base
}

allprojects {
    group = "com.chrisjenx.silo"
    version = providers.gradleProperty("silo.version").getOrElse("0.1.0-SNAPSHOT")
}

tasks.named("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
