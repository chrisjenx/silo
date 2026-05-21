plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        allWarningsAsErrors.set(false)
        freeCompilerArgs.addAll(
            "-Xjsr305=strict",
            "-Xjvm-default=all",
        )
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

ktlint {
    version.set("1.3.1")
    android.set(false)
    ignoreFailures.set(false)
    enableExperimentalRules.set(false)
    filter {
        exclude("**/build/**")
        exclude("**/generated/**")
    }
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(files("${rootProject.projectDir}/config/detekt/detekt.yml"))
    parallel = true
    autoCorrect = false
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "21"
    reports {
        xml.required.set(true)
        html.required.set(true)
        sarif.required.set(false)
        md.required.set(false)
    }
}

tasks.withType<io.gitlab.arturbosch.detekt.DetektCreateBaselineTask>().configureEach {
    jvmTarget = "21"
}
