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

    gauge("silo_storage_cross_fs_rename_total", Tags.empty(), cacheStore) { it.crossFsRenameCount.toDouble() }
    gauge("silo_drift_detected_total", Tags.of("kind", "missing_blob"), cacheStore) {
        it.enoentDriftCount.toDouble()
    }

    evictionEngine?.let { engine ->
        EvictionReason.entries.forEach { reason ->
            gauge(
                "silo_store_evictions_total",
                Tags.of("reason", reason.name.lowercase()),
                engine,
            ) { it.evictionsFor(reason).toDouble() }
        }
    }

    reconciliationEngine?.let { engine ->
        DriftKind.entries.forEach { kind ->
            gauge(
                "silo_drift_detected_total",
                Tags.of("kind", kind.name.lowercase()),
                engine,
            ) { it.driftDetected(kind).toDouble() }
        }
    }
}
