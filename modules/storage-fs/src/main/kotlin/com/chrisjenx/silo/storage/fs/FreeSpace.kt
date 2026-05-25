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

/** Live free-space readings for the volume backing the storage root. */
interface FreeSpace {
    /** Usable bytes remaining, or [Long.MAX_VALUE] when unknown. */
    fun usableBytes(): Long

    /** Free inodes remaining, or [Long.MAX_VALUE] when unknown/unsupported. */
    fun freeInodes(): Long
}

/** A [FreeSpace] that never reports pressure. Used as the default in tests. */
object UnlimitedFreeSpace : FreeSpace {
    override fun usableBytes(): Long = Long.MAX_VALUE

    override fun freeInodes(): Long = Long.MAX_VALUE
}

/**
 * Decides whether a PUT of [incomingBytes] may proceed without breaching the
 * configured free-space reserve. Pure logic over a [FreeSpace] reading so the
 * PUT path can pre-check before consuming the request body.
 */
class ReservedSpaceGuard(
    private val free: FreeSpace,
    private val reservedFreeBytes: Long,
    private val reservedFreeInodes: Long,
) {
    fun hasRoomFor(incomingBytes: Long): Boolean {
        // Nothing to enforce when both reserves are disabled — skip the probe
        // (avoids a per-PUT free-space syscall / df subprocess fork).
        if (reservedFreeBytes <= 0 && reservedFreeInodes <= 0) return true
        return free.usableBytes() - incomingBytes >= reservedFreeBytes &&
            free.freeInodes() >= reservedFreeInodes
    }
}
