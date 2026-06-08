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
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class AtomicJarReplacerSpec : StringSpec({

    "replace swaps in the new jar and keeps a .bak of the old one" {
        val dir = tempdir().toPath()
        val jar = dir.resolve("silo.jar").also { Files.writeString(it, "OLD") }
        val incoming = dir.resolve(".silo-update.tmp").also { Files.writeString(it, "NEW") }

        AtomicJarReplacer().replace(jar = jar, verifiedSource = incoming)

        Files.readString(jar) shouldBe "NEW"
        Files.readString(dir.resolve("silo.jar.bak")) shouldBe "OLD"
        Files.exists(incoming) shouldBe false
    }

    "rollback restores the previous jar from .bak" {
        val dir = tempdir().toPath()
        val jar = dir.resolve("silo.jar").also { Files.writeString(it, "OLD") }
        val incoming = dir.resolve(".silo-update.tmp").also { Files.writeString(it, "NEW") }
        val replacer = AtomicJarReplacer()
        replacer.replace(jar = jar, verifiedSource = incoming)

        replacer.rollback(jar) shouldBe true
        Files.readString(jar) shouldBe "OLD"
    }

    "rollback returns false when there is no .bak" {
        val dir = tempdir().toPath()
        val jar = dir.resolve("silo.jar").also { Files.writeString(it, "OLD") }
        AtomicJarReplacer().rollback(jar) shouldBe false
    }
})
