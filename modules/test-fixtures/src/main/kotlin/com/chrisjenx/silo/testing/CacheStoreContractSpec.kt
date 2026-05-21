/*
 * Copyright (c) 2026 Chris Jenkins
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

import io.kotest.core.spec.style.BehaviorSpec

/**
 * Shared contract every [com.chrisjenx.silo.storage.CacheStore] implementation
 * must satisfy. Backends provide a concrete spec that extends this class once
 * the `CacheStore` interface lands (issue #3).
 *
 * Stubbed here so the wiring exists from day one; scenarios are added when
 * the interface and the first FS backend appear.
 */
abstract class CacheStoreContractSpec :
    BehaviorSpec({
        given("a freshly initialized store") {
            `when`("the contract suite is wired up but the interface has not landed yet") {
                then("the spec compiles and is discoverable by kotest") {
                    // Intentionally empty — scenarios arrive with issue #3.
                }
            }
        }
    })
