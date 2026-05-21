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

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.deleteRecursively

/**
 * A self-cleaning temporary directory rooted under the JVM tmpdir, intended as
 * a CAS root for cache store tests. Call [close] (or use [use]) when finished.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class TmpCacheRoot private constructor(
    val path: Path,
) : AutoCloseable {
    override fun close() {
        path.deleteRecursively()
    }

    inline fun <R> use(block: (Path) -> R): R =
        try {
            block(path)
        } finally {
            close()
        }

    companion object {
        fun create(prefix: String = "silo-test-"): TmpCacheRoot = TmpCacheRoot(Files.createTempDirectory(prefix).absolute())
    }
}
