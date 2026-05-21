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

    inline fun <R> use(block: (Path) -> R): R = try {
        block(path)
    } finally {
        close()
    }

    companion object {
        fun create(prefix: String = "silo-test-"): TmpCacheRoot =
            TmpCacheRoot(Files.createTempDirectory(prefix).absolute())
    }
}
