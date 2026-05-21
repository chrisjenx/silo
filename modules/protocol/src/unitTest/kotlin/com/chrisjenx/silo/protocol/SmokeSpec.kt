package com.chrisjenx.silo.protocol

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class SmokeSpec : BehaviorSpec({
    given("the unit test source set") {
        `when`("kotest is wired correctly") {
            then("a trivial assertion passes") {
                (1 + 1) shouldBe 2
            }
        }
    }
})
