plugins {
    id("silo.kotlin-conventions")
    id("org.jetbrains.kotlinx.kover")
}

kover {
    reports {
        verify {
            rule("80% line coverage") {
                minBound(80, coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE)
            }
        }
        filters {
            excludes {
                packages("com.chrisjenx.silo.testing")
            }
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.named("koverVerify"))
}
