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
package com.chrisjenx.silo.server

import com.chrisjenx.silo.server.auth.AuthSettings
import com.chrisjenx.silo.storage.MetadataIndex
import com.chrisjenx.silo.storage.fs.FileSystemCacheStore
import com.chrisjenx.silo.storage.fs.ReconciliationEngine
import com.chrisjenx.silo.storage.fs.StorageRootLock
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry

/**
 * Container holding the lifecycle-bound services Silo routes consult.
 * Built once by [Application.module] (or by tests) and handed to
 * [installSiloModule].
 */
class SiloServices(
    val config: SiloConfig,
    val cacheStore: FileSystemCacheStore,
    val metadataIndex: MetadataIndex,
    val readinessProbe: ReadinessProbe,
    val storageRootLock: StorageRootLock?,
    val auth: AuthSettings,
    val reconciliationEngine: ReconciliationEngine,
    val meterRegistry: PrometheusMeterRegistry,
)
