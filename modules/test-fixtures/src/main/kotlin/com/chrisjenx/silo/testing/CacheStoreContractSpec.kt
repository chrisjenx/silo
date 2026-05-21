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
package com.chrisjenx.silo.testing

import com.chrisjenx.silo.protocol.CacheKey
import com.chrisjenx.silo.storage.CacheStore
import com.chrisjenx.silo.storage.PutOutcome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.readByteArray
import kotlinx.io.write
import kotlin.random.Random

/**
 * The behavioural contract every [CacheStore] implementation must satisfy.
 *
 * Backends ship their conformance suite as a concrete subclass that supplies
 * a factory via [createStore]. The base class drives the scenarios; the
 * subclass is responsible for fresh per-test instances and clean-up.
 */
abstract class CacheStoreContractSpec : BehaviorSpec() {
    /**
     * Build a fresh, isolated [CacheStore] for a single test. [maxEntryBytes]
     * controls the per-entry size cap the implementation should enforce.
     */
    protected abstract suspend fun createStore(maxEntryBytes: Long = DEFAULT_MAX_ENTRY_BYTES): CacheStore

    init {
        given("a CacheStore") {

            `when`("an entry is written and read back") {
                then("the round-trip preserves the exact bytes") {
                    val store = createStore()
                    val key = CacheKey.requireValid(TestKeys.valid(seed = 1))
                    val payload = randomPayload(seed = 1, size = 4096)

                    val outcome = store.put(key, payload.size.toLong(), payload.asRawSource())
                    outcome.shouldBeInstanceOf<PutOutcome.Stored>()
                    outcome.sizeBytes shouldBe payload.size.toLong()

                    val handle = store.get(key).shouldNotBeNull()
                    handle.use {
                        it.sizeBytes shouldBe payload.size.toLong()
                        it.body.readAllBytes() shouldBe payload
                    }
                }
            }

            `when`("get is requested for an absent key") {
                then("the store returns null and has() is false") {
                    val store = createStore()
                    val key = CacheKey.requireValid(TestKeys.valid(seed = 2))
                    store.get(key) shouldBe null
                    store.has(key) shouldBe false
                }
            }

            `when`("the same key is written by N concurrent writers") {
                then("exactly one valid blob remains and reads return one of the payloads") {
                    val store = createStore()
                    val key = CacheKey.requireValid(TestKeys.valid(seed = 3))
                    val payloads =
                        (0 until CONCURRENT_WRITERS).map {
                            randomPayload(seed = it.toLong(), size = 2048)
                        }

                    val outcomes =
                        coroutineScope {
                            payloads.map { payload ->
                                async {
                                    store.put(key, payload.size.toLong(), payload.asRawSource())
                                }
                            }.awaitAll()
                        }

                    outcomes.shouldHaveSize(CONCURRENT_WRITERS)
                    outcomes.forEach { it.shouldBeInstanceOf<PutOutcome>() }

                    val handle = store.get(key).shouldNotBeNull()
                    val read = handle.use { it.body.readAllBytes() }
                    // Last-writer-wins is acceptable; we only assert no corruption.
                    payloads.any { it.contentEquals(read) } shouldBe true
                }
            }

            `when`("a malformed key is passed to the protocol parser") {
                then("the parser rejects it before the store is touched") {
                    CacheKey.parse(TestKeys.tooShort) shouldBe null
                    CacheKey.parse(TestKeys.tooLong) shouldBe null
                    CacheKey.parse(TestKeys.pathTraversal) shouldBe null
                    CacheKey.parse(TestKeys.mixedCase) shouldBe null
                    CacheKey.parse(TestKeys.nonHex) shouldBe null

                    shouldThrow<IllegalArgumentException> {
                        CacheKey.requireValid(TestKeys.pathTraversal)
                    }
                }
            }

            `when`("the declared body size exceeds the per-entry cap") {
                then("put returns RejectedTooLarge and the blob is not committed") {
                    val cap = 1_024L
                    val store = createStore(maxEntryBytes = cap)
                    val key = CacheKey.requireValid(TestKeys.valid(seed = 4))
                    val payload = randomPayload(seed = 4, size = (cap + 1).toInt())

                    val outcome = store.put(key, payload.size.toLong(), payload.asRawSource())
                    outcome.shouldBeInstanceOf<PutOutcome.RejectedTooLarge>()

                    store.has(key) shouldBe false
                    store.get(key) shouldBe null
                }
            }

            `when`("the same identical bytes are written twice") {
                then("the second put returns either Stored or AlreadyPresent (backend's choice)") {
                    val store = createStore()
                    val key = CacheKey.requireValid(TestKeys.valid(seed = 5))
                    val payload = randomPayload(seed = 5, size = 4096)

                    val first = store.put(key, payload.size.toLong(), payload.asRawSource())
                    val second = store.put(key, payload.size.toLong(), payload.asRawSource())

                    first.shouldBeInstanceOf<PutOutcome.Stored>()
                    when (second) {
                        is PutOutcome.Stored, is PutOutcome.AlreadyPresent -> Unit
                        is PutOutcome.RejectedTooLarge ->
                            error("second put must not be rejected when first succeeded")
                        is PutOutcome.NoSpace ->
                            error("second put must not fail with NoSpace when first succeeded")
                    }
                    store.has(key) shouldBe true
                }
            }
        }
    }

    private fun randomPayload(
        seed: Long,
        size: Int,
    ): ByteArray = ByteArray(size).also { Random(seed).nextBytes(it) }

    private fun ByteArray.asRawSource(): RawSource = Buffer().apply { write(this@asRawSource) }

    private fun RawSource.readAllBytes(): ByteArray =
        Buffer().also { sink ->
            while (true) {
                val read = readAtMostTo(sink, Long.MAX_VALUE)
                if (read == -1L) break
            }
        }.readByteArray()

    companion object {
        const val DEFAULT_MAX_ENTRY_BYTES: Long = 2L * 1024 * 1024 * 1024
        const val CONCURRENT_WRITERS: Int = 8
    }
}
