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

import com.chrisjenx.silo.protocol.CacheKey
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Paths

class ShardLayoutSpec : StringSpec({
    val root = Paths.get("/tmp/silo")
    val key = CacheKey.requireValid("abcd1234deadbeef")

    "shardDir uses two-level hex prefix" {
        ShardLayout.shardDir(root, key).toString() shouldBe "/tmp/silo/cas/ab/cd"
    }

    "finalPath joins shard dir and full key" {
        ShardLayout.finalPath(root, key).toString() shouldBe "/tmp/silo/cas/ab/cd/abcd1234deadbeef"
    }

    "tempPath includes the UUID suffix in the same shard dir" {
        ShardLayout.tempPath(root, key, "u-1").toString() shouldBe
            "/tmp/silo/cas/ab/cd/tmp.abcd1234deadbeef.u-1"
    }
})
