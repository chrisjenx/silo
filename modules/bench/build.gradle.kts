import kotlinx.benchmark.gradle.JvmBenchmarkTarget

plugins {
    id("silo.kotlin-conventions")
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.kotlinx.benchmark)
}

// JMH needs benchmark state classes to be open; allopen rewrites them.
allOpen {
    annotation("kotlinx.benchmark.State")
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(project(":protocol"))
    implementation(libs.kotlinx.benchmark.runtime)
}

benchmark {
    targets {
        register("main") {
            this as JvmBenchmarkTarget
            jmhVersion = "1.37"
        }
    }
    configurations {
        named("main") {
            warmups = 5
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
        }
    }
}
