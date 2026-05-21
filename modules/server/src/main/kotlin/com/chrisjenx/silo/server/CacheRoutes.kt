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

import com.chrisjenx.silo.protocol.CacheKey
import com.chrisjenx.silo.protocol.ContentTypes
import com.chrisjenx.silo.storage.CacheStore
import com.chrisjenx.silo.storage.PutOutcome
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.io.readByteArray

/**
 * Mounts the Gradle build-cache protocol routes under `/cache/{key}`.
 *
 * The key parameter is validated against [CacheKey.parse] at the boundary;
 * downstream code is allowed to assume the key is a safe filesystem
 * component (no traversal, no separators).
 */
fun Route.cacheRoutes(store: CacheStore) {
    route("/cache/{key}") {
        get {
            val raw = call.parameters["key"].orEmpty()
            val key = CacheKey.parse(raw)
            if (key == null) {
                call.respond(HttpStatusCode.BadRequest)
                return@get
            }
            val handle = store.get(key)
            if (handle == null) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            try {
                val size = handle.sizeBytes
                val source = handle.body.buffered()
                val bodyBytes = source.readByteArray(size.toInt())
                call.respondBytes(
                    bytes = bodyBytes,
                    contentType = ContentType.parse(ContentTypes.CACHE_BODY),
                    status = HttpStatusCode.OK,
                )
            } finally {
                handle.close()
            }
        }

        put {
            val raw = call.parameters["key"].orEmpty()
            val key = CacheKey.parse(raw)
            if (key == null) {
                call.respond(HttpStatusCode.BadRequest)
                return@put
            }
            val declaredSize = call.request.contentLength()
            if (declaredSize == null || declaredSize < 0) {
                call.respond(HttpStatusCode.LengthRequired)
                return@put
            }
            val channel = call.receiveChannel()
            val source = channel.toInputStream().asSource().buffered()
            val outcome = store.put(key, declaredSize, source)
            when (outcome) {
                is PutOutcome.Stored, is PutOutcome.AlreadyPresent ->
                    call.respond(HttpStatusCode.OK)
                is PutOutcome.RejectedTooLarge ->
                    call.respond(HttpStatusCode.PayloadTooLarge)
            }
        }
    }
}
