import groovy.json.JsonSlurper
import kotlinx.benchmark.gradle.JvmBenchmarkTarget
import java.io.File

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

// --- k6 latency regression gate (bench.yml) -------------------------------
//
// Parses two k6 `--summary-export` JSON files and fails if any tracked
// percentile of the chosen metric regresses by more than -Pthreshold.
// Writes a markdown table to -Psummary for the PR comment.
//
//   ./gradlew :bench:compareBaseline \
//       -Pcurrent=bench-results.json \
//       -Pbaseline=bench/baselines/main.json \
//       -Pthreshold=0.10
//
// Paths are resolved against the repo root. A missing/empty baseline
// records the current numbers and skips the gate (first run on a branch).
val percentileKeys = listOf("p50" to "med", "p95" to "p(95)", "p99" to "p(99)")

fun resolveFromRoot(path: String): File = File(path).let { if (it.isAbsolute) it else rootProject.file(path) }

@Suppress("UNCHECKED_CAST")
fun readTrend(
    file: File,
    metric: String,
): Map<String, Double> {
    val parsed = JsonSlurper().parse(file) as Map<String, Any?>
    val metrics =
        parsed["metrics"] as? Map<String, Any?>
            ?: throw GradleException("No 'metrics' object in ${file.name}")
    val trend =
        metrics[metric] as? Map<String, Any?>
            ?: throw GradleException("Metric '$metric' not found in ${file.name}")
    return percentileKeys.associate { (label, k6Key) ->
        val value =
            (trend[k6Key] as? Number)?.toDouble()
                ?: throw GradleException(
                    "Stat '$k6Key' missing in ${file.name}; add it to summaryTrendStats in the k6 script",
                )
        label to value
    }
}

tasks.register("compareBaseline") {
    group = "verification"
    description = "Fail the build if a k6 percentile regresses past -Pthreshold versus the baseline."

    doLast {
        val metric = (project.findProperty("metric") as String?) ?: "http_req_duration"
        val threshold = (project.findProperty("threshold") as String?)?.toDouble() ?: 0.10
        val currentArg =
            project.findProperty("current") as String?
                ?: throw GradleException("Missing -Pcurrent=<k6 summary json>")
        val summaryFile = resolveFromRoot((project.findProperty("summary") as String?) ?: "bench-summary.md")

        val current = readTrend(resolveFromRoot(currentArg), metric)
        val baselineFile = (project.findProperty("baseline") as String?)?.let(::resolveFromRoot)
        val haveBaseline = baselineFile != null && baselineFile.exists() && baselineFile.length() > 0L

        val md = StringBuilder()
        md.appendLine("### k6 `$metric` regression gate")
        md.appendLine()

        if (!haveBaseline) {
            md.appendLine("No committed baseline yet — recording current numbers, gate skipped.")
            md.appendLine()
            md.appendLine("| metric | current (ms) |")
            md.appendLine("|---|---|")
            percentileKeys.forEach { (label, _) -> md.appendLine("| $label | ${"%.2f".format(current.getValue(label))} |") }
            summaryFile.writeText(md.toString())
            logger.lifecycle("No baseline found; wrote ${summaryFile.name}, gate skipped.")
            return@doLast
        }

        val baseline = readTrend(baselineFile, metric)
        val regressions = mutableListOf<String>()
        md.appendLine("Fails if current > baseline × ${"%.2f".format(1 + threshold)} on any percentile.")
        md.appendLine()
        md.appendLine("| metric | baseline (ms) | current (ms) | Δ | status |")
        md.appendLine("|---|---|---|---|---|")
        percentileKeys.forEach { (label, _) ->
            val base = baseline.getValue(label)
            val cur = current.getValue(label)
            val delta = if (base > 0.0) (cur - base) / base else 0.0
            val regressed = delta > threshold
            if (regressed) regressions += "$label +${"%.1f".format(delta * 100)}%"
            val status = if (regressed) "❌ FAIL" else "✅"
            md.appendLine(
                "| $label | ${"%.2f".format(base)} | ${"%.2f".format(cur)} | ${"%+.1f".format(delta * 100)}% | $status |",
            )
        }
        summaryFile.writeText(md.toString())
        logger.lifecycle("\n" + md.toString())

        if (regressions.isNotEmpty()) {
            throw GradleException(
                "Latency regression past ${(threshold * 100).toInt()}%: ${regressions.joinToString()}",
            )
        }
    }
}
