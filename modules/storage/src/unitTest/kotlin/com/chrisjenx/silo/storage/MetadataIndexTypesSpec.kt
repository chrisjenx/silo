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
package com.chrisjenx.silo.storage

import com.chrisjenx.silo.protocol.CacheKey
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class MetadataIndexTypesSpec : StringSpec({

    val key = CacheKey.requireValid("abcdef0123456789abcdef")
    val sha = ByteArray(32) { 0xAB.toByte() }

    "EntryStatus.fromCode round-trips both codes" {
        EntryStatus.fromCode(EntryStatus.COMMITTED.code) shouldBe EntryStatus.COMMITTED
        EntryStatus.fromCode(EntryStatus.TOMBSTONED.code) shouldBe EntryStatus.TOMBSTONED
    }

    "EntryRecord equals handles content-equal sha256 byte arrays" {
        val a = EntryRecord(key, 1024, 10, 20, sha.copyOf(), EntryStatus.COMMITTED)
        val b = EntryRecord(key, 1024, 10, 20, sha.copyOf(), EntryStatus.COMMITTED)
        a shouldBe b
        a.hashCode() shouldBe b.hashCode()
    }

    "EntryRecord equals returns false when fields differ" {
        val base = EntryRecord(key, 1024, 10, 20, sha, EntryStatus.COMMITTED)
        base shouldNotBe base.copy(sizeBytes = 2048)
        base shouldNotBe base.copy(insertedAtMs = 99)
        base shouldNotBe base.copy(lastAccessMs = 99)
        base shouldNotBe base.copy(status = EntryStatus.TOMBSTONED)
    }

    "EntryRecord equals treats null sha256 as different from non-null" {
        val withSha = EntryRecord(key, 1024, 10, 20, sha, EntryStatus.COMMITTED)
        val noSha = EntryRecord(key, 1024, 10, 20, null, EntryStatus.COMMITTED)
        withSha shouldNotBe noSha
        EntryRecord(key, 1024, 10, 20, null, EntryStatus.COMMITTED) shouldBe noSha
    }

    "EntryRecord equals short-circuits on identity and rejects other types" {
        val r = EntryRecord(key, 1024, 10, 20, sha, EntryStatus.COMMITTED)
        @Suppress("ReplaceCallWithBinaryOperator")
        r.equals(r) shouldBe true
        @Suppress("ReplaceCallWithBinaryOperator")
        r.equals("not an EntryRecord") shouldBe false
    }

    "MetadataAggregate exposes count and bytes" {
        val agg = MetadataAggregate(entryCount = 5, bytesStored = 12_345)
        agg.entryCount shouldBe 5L
        agg.bytesStored shouldBe 12_345L
    }
})
