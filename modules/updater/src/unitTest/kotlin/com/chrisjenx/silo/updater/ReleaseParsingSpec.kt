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
package com.chrisjenx.silo.updater

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ReleaseParsingSpec : StringSpec({
    val json =
        """
        {"tag_name":"v0.2.0","prerelease":false,"assets":[
          {"name":"silo.jar","browser_download_url":"https://example/silo.jar"},
          {"name":"checksums.txt","browser_download_url":"https://example/checksums.txt"}
        ]}
        """.trimIndent()

    "maps a GitHub release payload to the domain Release" {
        val r = Release.fromJson(json)
        r.tag shouldBe "v0.2.0"
        r.version shouldBe SemVer(0, 2, 0)
        r.prerelease shouldBe false
        r.asset("silo.jar")?.downloadUrl shouldBe "https://example/silo.jar"
        r.asset("missing") shouldBe null
    }
})
