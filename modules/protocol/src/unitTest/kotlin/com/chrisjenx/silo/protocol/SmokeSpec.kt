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

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class SmokeSpec :
    BehaviorSpec({
        given("the unit test source set") {
            `when`("kotest is wired correctly") {
                then("a trivial assertion passes") {
                    (1 + 1) shouldBe 2
                }
            }
        }
    })
