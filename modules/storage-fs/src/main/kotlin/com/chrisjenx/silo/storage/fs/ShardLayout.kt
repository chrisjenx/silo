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
import java.nio.file.Path

/**
 * Two-level hex sharding: `cas/{ab}/{cd}/{key}`. At 1M entries that's ~15
 * files per leaf directory, which keeps ext4 (with `dir_index`) and APFS
 * lookups fast. Temp files live in the same leaf directory as the final so
 * `Files.move(ATOMIC_MOVE)` never crosses a filesystem boundary.
 */
object ShardLayout {
    /** Returns the leaf directory `cas/{ab}/{cd}` under [root] for [key]. */
    fun shardDir(
        root: Path,
        key: CacheKey,
    ): Path {
        val raw = key.value
        // CacheKey guarantees length >= MIN_LENGTH (8) and all-hex, so the
        // first four chars are always safe shard names.
        val ab = raw.substring(0, 2)
        val cd = raw.substring(2, 4)
        return root.resolve("cas").resolve(ab).resolve(cd)
    }

    /** Final blob path: `cas/{ab}/{cd}/{key}`. */
    fun finalPath(
        root: Path,
        key: CacheKey,
    ): Path = shardDir(root, key).resolve(key.value)

    /**
     * Temp file path inside the leaf shard dir. Includes a UUID suffix so two
     * concurrent PUTs of the same key never alias on the same tmp file.
     */
    fun tempPath(
        root: Path,
        key: CacheKey,
        uuid: String,
    ): Path = shardDir(root, key).resolve("tmp.${key.value}.$uuid")
}
