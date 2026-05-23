/*
 * Copyright 2026 Silo contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.chrisjenx.silo.metrics

import com.chrisjenx.silo.storage.EvictionEngine
import com.chrisjenx.silo.storage.EvictionReason
import com.chrisjenx.silo.storage.fs.DriftKind
import com.chrisjenx.silo.storage.fs.FileSystemCacheStore
import com.chrisjenx.silo.storage.fs.ReconciliationEngine
import io.micrometer.core.instrument.FunctionCounter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics

/**
 * Binds the project's lifetime counters to a Micrometer registry so they
 * scrape as `silo_*` Prometheus series. Designed to be called once at
 * boot — gauges read from the engines' AtomicLong counters lazily, so
 * the registry never lags behind the source of truth.
 */
fun MeterRegistry.bindSilo(
    cacheStore: FileSystemCacheStore,
    evictionEngine: EvictionEngine? = null,
    reconciliationEngine: ReconciliationEngine? = null,
) {
    JvmMemoryMetrics().bindTo(this)
    JvmGcMetrics().bindTo(this)
    JvmThreadMetrics().bindTo(this)
    ProcessorMetrics().bindTo(this)

    // Monotonic lifetime counts → Prometheus counters. Micrometer appends the
    // `_total` suffix, so the base names are given without it (e.g.
    // `silo_drift_detected` is scraped as `silo_drift_detected_total`).
    counter("silo_storage_cross_fs_rename", Tags.empty(), cacheStore) { it.crossFsRenameCount.toDouble() }
    counter("silo_drift_detected", Tags.of("kind", "missing_blob"), cacheStore) {
        it.enoentDriftCount.toDouble()
    }

    evictionEngine?.let { engine ->
        EvictionReason.entries.forEach { reason ->
            counter(
                "silo_store_evictions",
                Tags.of("reason", reason.name.lowercase()),
                engine,
            ) { it.evictionsFor(reason).toDouble() }
        }
    }

    reconciliationEngine?.let { engine ->
        DriftKind.entries.forEach { kind ->
            counter(
                "silo_drift_detected",
                Tags.of("kind", kind.name.lowercase()),
                engine,
            ) { it.driftDetected(kind).toDouble() }
        }
    }
}

/**
 * Registers a Prometheus counter backed by a monotonic lifetime count read
 * lazily from [state]. Mirrors Micrometer's `gauge(name, tags, obj, fn)` shape.
 */
private fun <T : Any> MeterRegistry.counter(
    name: String,
    tags: Tags,
    state: T,
    value: (T) -> Double,
) {
    FunctionCounter.builder(name, state) { value(it) }
        .tags(tags)
        .register(this)
}
