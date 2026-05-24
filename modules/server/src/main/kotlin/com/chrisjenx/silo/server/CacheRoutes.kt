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
import com.chrisjenx.silo.server.auth.AuthSettings
import com.chrisjenx.silo.server.auth.Role
import com.chrisjenx.silo.server.auth.SiloPrincipal
import com.chrisjenx.silo.server.auth.authenticateSilo
import com.chrisjenx.silo.storage.CacheStore
import com.chrisjenx.silo.storage.PutOutcome
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.io.readByteArray

/**
 * Mounts the Gradle build-cache protocol routes under `/cache/{key}`.
 *
 * Auth posture:
 * - PUT always requires a WRITE-role principal.
 * - GET/HEAD require a READ-role principal unless [AuthSettings.anonymousRead]
 *   is true, in which case anonymous requests are served.
 */
fun Route.cacheRoutes(
    store: CacheStore,
    auth: AuthSettings,
    maxEntryBytes: Long,
) {
    authenticateSilo(auth, optional = true) {
        route("/cache/{key}") {
            get { call.handleGet(store, auth) }
            head { call.handleHead(store, auth) }
            put { call.handlePut(store, maxEntryBytes) }
        }
    }
}

private suspend fun ApplicationCall.handleGet(
    store: CacheStore,
    auth: AuthSettings,
) {
    if (!authorize(Role.READ, allowAnonymous = auth.anonymousRead)) return
    val key = parsedKey() ?: return respond(HttpStatusCode.BadRequest)
    val handle = store.get(key) ?: return respond(HttpStatusCode.NotFound)
    try {
        val size = handle.sizeBytes
        val source = handle.body.buffered()
        val bodyBytes = source.readByteArray(size.toInt())
        respondBytes(
            bytes = bodyBytes,
            contentType = ContentType.parse(ContentTypes.CACHE_BODY),
            status = HttpStatusCode.OK,
        )
    } finally {
        handle.close()
    }
}

private suspend fun ApplicationCall.handleHead(
    store: CacheStore,
    auth: AuthSettings,
) {
    if (!authorize(Role.READ, allowAnonymous = auth.anonymousRead)) return
    val key = parsedKey() ?: return respond(HttpStatusCode.BadRequest)
    val handle = store.get(key) ?: return respond(HttpStatusCode.NotFound)
    try {
        response.headers.append(HttpHeaders.ContentLength, handle.sizeBytes.toString())
        response.headers.append(HttpHeaders.ContentType, ContentTypes.CACHE_BODY)
        respond(HttpStatusCode.OK)
    } finally {
        handle.close()
    }
}

private suspend fun ApplicationCall.handlePut(
    store: CacheStore,
    maxEntryBytes: Long,
) {
    if (!authorize(Role.WRITE, allowAnonymous = false)) return
    val key = parsedKey() ?: return respond(HttpStatusCode.BadRequest)
    val declaredSize = request.contentLength()
    if (declaredSize == null || declaredSize < 0) {
        return respond(HttpStatusCode.LengthRequired)
    }
    // Reject oversize before reading the body — short-circuits Ktor's
    // Expect: 100-continue handshake so the client does not upload.
    if (declaredSize > maxEntryBytes) {
        return respond(HttpStatusCode.PayloadTooLarge)
    }
    val source = receiveChannel().toInputStream().asSource().buffered()
    when (store.put(key, declaredSize, source)) {
        is PutOutcome.Stored, is PutOutcome.AlreadyPresent ->
            respond(HttpStatusCode.OK)
        is PutOutcome.RejectedTooLarge ->
            respond(HttpStatusCode.PayloadTooLarge)
        is PutOutcome.NoSpace ->
            respond(HttpStatusCode.ServiceUnavailable)
    }
}

private suspend fun ApplicationCall.authorize(
    role: Role,
    allowAnonymous: Boolean,
): Boolean {
    val principal = principal<SiloPrincipal>()
    return when {
        principal != null && role in principal.roles -> true
        principal == null && allowAnonymous -> true
        principal == null -> {
            response.headers.append(HttpHeaders.WWWAuthenticate, "Basic realm=\"silo\", charset=\"UTF-8\"")
            respond(HttpStatusCode.Unauthorized)
            false
        }
        else -> {
            respond(HttpStatusCode.Forbidden)
            false
        }
    }
}

private fun ApplicationCall.parsedKey(): CacheKey? = CacheKey.parse(parameters["key"].orEmpty())
