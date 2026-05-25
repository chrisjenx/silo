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
package com.chrisjenx.silo.storage.fs

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

private fun freeSpace(
    bytes: Long,
    inodes: Long,
) = object : FreeSpace {
    override fun usableBytes() = bytes

    override fun freeInodes() = inodes
}

class ReservedSpaceGuardSpec : StringSpec({

    "allows a write that still leaves the byte + inode reserve" {
        val guard =
            ReservedSpaceGuard(
                free = freeSpace(bytes = 10_000, inodes = 1_000),
                reservedFreeBytes = 5_000,
                reservedFreeInodes = 100,
            )
        guard.hasRoomFor(4_000) shouldBe true
    }

    "denies a write that would dip below the byte reserve" {
        val guard =
            ReservedSpaceGuard(
                free = freeSpace(bytes = 10_000, inodes = 1_000),
                reservedFreeBytes = 5_000,
                reservedFreeInodes = 100,
            )
        guard.hasRoomFor(6_000) shouldBe false
    }

    "denies a write when free inodes are below the reserve" {
        val guard =
            ReservedSpaceGuard(
                free = freeSpace(bytes = Long.MAX_VALUE, inodes = 50),
                reservedFreeBytes = 5_000,
                reservedFreeInodes = 100,
            )
        guard.hasRoomFor(1) shouldBe false
    }
})
