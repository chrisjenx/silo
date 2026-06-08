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
import io.kotest.matchers.string.shouldContain

class JarLocatorSpec : StringSpec({

    "refuses a dev/unversioned build" {
        val dir = tempdir()
        val jar = dir.resolve("silo.jar").also { it.writeBytes(byteArrayOf(1)) }
        val r = JarLocator.locate(currentVersion = "dev", codeSource = jar.toPath())
        (r as UpdateOutcome.Failed).reason shouldContain "dev"
    }

    "refuses when the code source is a directory (exploded classpath / :run)" {
        val dir = tempdir()
        val r = JarLocator.locate(currentVersion = "0.1.3", codeSource = dir.toPath())
        (r as UpdateOutcome.Failed).reason shouldContain "packaged jar"
    }

    "refuses when the jar's directory is not writable" {
        val dir = tempdir()
        val jar = dir.resolve("silo.jar").also { it.writeBytes(byteArrayOf(1)) }
        dir.setWritable(false)
        try {
            val r = JarLocator.locate(currentVersion = "0.1.3", codeSource = jar.toPath())
            (r as UpdateOutcome.Failed).reason shouldContain "image"
        } finally {
            dir.setWritable(true)
        }
    }

    "resolves a writable packaged jar" {
        val dir = tempdir()
        val jar = dir.resolve("silo.jar").also { it.writeBytes(byteArrayOf(1)) }
        val r = JarLocator.locate(currentVersion = "0.1.3", codeSource = jar.toPath())
        (r as JarLocated).path shouldBe jar.toPath()
    }
})
