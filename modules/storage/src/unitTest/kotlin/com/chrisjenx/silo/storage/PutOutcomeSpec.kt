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

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class PutOutcomeSpec : StringSpec({

    "Stored carries the committed size" {
        val o = PutOutcome.Stored(1234L)
        o.shouldBeInstanceOf<PutOutcome>()
        o.sizeBytes shouldBe 1234L
    }

    "AlreadyPresent carries the committed size" {
        val o = PutOutcome.AlreadyPresent(1234L)
        o.shouldBeInstanceOf<PutOutcome>()
        o.sizeBytes shouldBe 1234L
    }

    "RejectedTooLarge carries the offending size and the cap" {
        val o = PutOutcome.RejectedTooLarge(sizeBytes = 9_000L, maxBytes = 2_000L)
        o.shouldBeInstanceOf<PutOutcome>()
        o.sizeBytes shouldBe 9_000L
        o.maxBytes shouldBe 2_000L
    }

    "equality follows data-class semantics" {
        PutOutcome.Stored(7L) shouldBe PutOutcome.Stored(7L)
        (PutOutcome.Stored(7L) == PutOutcome.AlreadyPresent(7L)) shouldBe false
    }
})
