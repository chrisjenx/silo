# :bench — JMH micro-benchmarks

kotlinx-benchmark (JMH backend) micro-benchmarks for the hot paths that
sit on every request. JVM-only module; not part of the coverage gate.

```bash
./gradlew :bench:benchmark          # run every @Benchmark
./gradlew :bench:compileKotlin      # just compile (CI smoke)
```

Results land under `modules/bench/build/reports/benchmarks/`.

## Benchmarks

| Class                        | What                                              |
|------------------------------|---------------------------------------------------|
| `CacheKeyValidationBench`    | `CacheKey.parse` throughput, valid + reject paths |

Each benchmark op validates a batch of 1024 keys, so multiply the
reported `ops/s` by 1024 for per-key throughput.

## Adding a benchmark

1. Drop a `@State(Scope.Benchmark)` class under
   `src/main/kotlin/com/chrisjenx/silo/bench/`.
2. Annotate hot methods with `@Benchmark`; take a `Blackhole` and
   `consume(...)` results so the JIT cannot elide the work.
3. State classes are opened automatically by the allopen plugin
   (`kotlinx.benchmark.State`) — no `open` keyword needed.

Planned: `ShardingBench` (path allocation), `LruIndexBench`
(`MetadataIndex.lruVictims` contention), `AtomicWriterBench`
(tmp-write + atomic-rename throughput).
