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

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Strategy for committing a temp file to its final location.
 *
 * The default implementation calls [Files.move] with
 * [StandardCopyOption.ATOMIC_MOVE]; that's the durability boundary the
 * bootstrap plan calls for. Tests inject a stub that throws
 * [AtomicMoveNotSupportedException] to exercise the cross-FS fallback
 * path without needing two real filesystems to be present.
 */
fun interface AtomicMover {
    /**
     * Atomically rename [tmp] over [final], replacing any existing file.
     *
     * @throws AtomicMoveNotSupportedException when the underlying
     *   filesystem cannot guarantee atomicity — typically because the
     *   source and target are on different mounts.
     */
    fun move(
        tmp: Path,
        final: Path,
    )
}

/** Production [AtomicMover] — a thin wrapper around [Files.move]. */
object DefaultAtomicMover : AtomicMover {
    override fun move(
        tmp: Path,
        final: Path,
    ) {
        Files.move(
            tmp,
            final,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
}
