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
