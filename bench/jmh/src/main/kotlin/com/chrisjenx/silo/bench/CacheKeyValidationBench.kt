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
package com.chrisjenx.silo.bench

// Placeholder for the kotlinx-benchmark micro-benchmark. The JMH
// plugin is not wired into Gradle yet — see ../README.md — so these
// sources are written to the kotlinx-benchmark @Benchmark API and
// will compile-in-place once the plugin lands.
//
// Skeleton form:
//
//   @State(Scope.Benchmark)
//   open class CacheKeyValidationBench {
//       private val keys = (1..1024).map { hexKey(it.toLong()) }
//
//       @Benchmark
//       fun parseValid(): Int {
//           var hits = 0
//           for (k in keys) if (CacheKey.parse(k) != null) hits += 1
//           return hits
//       }
//   }
//
// Target: > 50M ops/sec/core on commodity laptop CPUs.
