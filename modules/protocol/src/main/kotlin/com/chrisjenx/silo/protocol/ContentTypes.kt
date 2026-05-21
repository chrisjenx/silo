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
package com.chrisjenx.silo.protocol

/** HTTP content-type constants used by the Gradle build-cache protocol. */
object ContentTypes {
    /** The cache body is opaque binary. */
    const val CACHE_BODY: String = "application/vnd.gradle.build-cache-artifact.v1"

    /** Fallback we accept on read in case older clients send octet-stream. */
    const val OCTET_STREAM: String = "application/octet-stream"

    /** Content-type for `/metrics` (Prometheus text exposition). */
    const val PROMETHEUS_TEXT: String = "text/plain; version=0.0.4; charset=utf-8"

    /** Content-type for JSON admin endpoints. */
    const val JSON: String = "application/json; charset=utf-8"
}
