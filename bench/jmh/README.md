# silo · JMH micro-benchmarks (stub)

This directory will host the `:bench` Gradle module once the
kotlinx-benchmark plugin lands. The Kotlin sources here are written
against the kotlinx-benchmark `@Benchmark` annotation API so they
can be wired in directly when the plugin is added.

## Planned benchmarks

| File                          | What                                                | Target              |
|-------------------------------|-----------------------------------------------------|---------------------|
| `CacheKeyValidationBench.kt`  | Throughput of `CacheKey.parse` on a hex set         | > 50 M ops/s/core   |
| `ShardingBench.kt`            | Allocation rate of `ShardLayout.finalPath`          | track over time     |
| `LruIndexBench.kt`            | Contention of `MetadataIndex.lruVictims` at 1/4/16/64 threads | scale linearly |
| `AtomicWriterBench.kt`        | tmp-write + atomic-rename throughput                | track over time     |

When the JMH plugin is wired up:

```bash
./gradlew :bench:benchmark
```

JSON output drops into `bench/results/` next to the k6 results, so
`bench.yml` can read both with one parser.
