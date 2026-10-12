# Local Hibernate delegate measurements

Recorded 2026-10-12 with native GlobalID mappings.

PostgreSQL; 5,000 related Person rows; 100-row forward connection; Java 21.0.6.
Three warmup rounds and 20 alternating-order samples per case. Each sample opens, initializes,
reads, commits, and closes a fresh session against the same data. Setup and compilation are excluded.
Prepared SQL counts exclude the two transaction-local timeout settings executed through JDBC.
Allocation is measured on the IO thread with the JDK ThreadMXBean;
it excludes server allocation and unrelated threads.

| Operation | Median ms | p95 ms | Median allocated KiB | Entities loaded | Collections loaded | SQL statements |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Collection: entity hydration | 61.58 | 90.03 | 64839.55 | 5001 | 1 | 2 |
| Collection: ID projection | 19.87 | 36.07 | 17992.51 | 0 | 0 | 2 |
| Connection: entity hydration | 6.34 | 14.37 | 1579.03 | 101 | 0 | 1 |
| Connection: ID projection | 5.60 | 17.07 | 764.27 | 0 | 0 | 1 |

These are local exploratory measurements using Hibernate's test connection pool, small scalar
payloads, and warm database caches. They do not establish production throughput or latency.
Lists still materialize every reference. Connections fetch the requested page plus one row.

## Native Session migration and shared-access feasibility — 2026-10-12

The following comparison uses the same native Session/transaction wiring in both runs.
The baseline retains all generated bean accessors; the prototype removes only Person.username's
get/set methods and installs a test-only public PropertyAccessStrategy. Hibernate writes that
String directly into the existing public GRT builder through method handles resolved at bootstrap.
Other fields and associations keep their generated accessors. Production generation is unchanged.

| Operation | Baseline median ms | Prototype median ms | Baseline p95 ms | Prototype p95 ms | Baseline allocated KiB | Prototype allocated KiB |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Collection: entity hydration | 59.00 | 58.04 | 68.78 | 70.13 | 64839.66 | 65542.84 |
| Collection: ID projection | 22.61 | 20.16 | 33.28 | 30.93 | 17992.54 | 17992.59 |
| Connection: entity hydration | 6.78 | 5.62 | 9.85 | 11.76 | 1579.08 | 1593.27 |
| Connection: ID projection | 5.24 | 4.52 | 6.60 | 8.72 | 764.30 | 764.30 |

Entity, collection, and SQL counts match the table above. Both runs pass comparisons that preserve
bag duplicates and exact connection ordering, IDs, cursors, and PageInfo. Selective reads hydrate
no entities or bags.

These separate local runs show similar latency and approximately 1% more allocation in the
prototype's entity-hydration cases. Control projection timings also vary; this is not evidence
of a latency improvement. Replacing one String field proves feasibility, not full-schema support
or net code reduction. Keep the shared adapter test-only until broader schema coverage and
generated-plus-runtime source size justify adopting it.

Reproduce using the existing PostgreSQL test environment:

```sh
./gradlew :plugin:delegatePerformanceTest --console=plain
GRT_BUILDER_ACCESS_PROTOTYPE=true ./gradlew :plugin:delegatePerformanceTest --console=plain
```

Each run overwrites plugin/build/reports/hibernate-delegates/performance.md. These recorded
reports were saved locally as /private/tmp/grt-native-baseline-performance.md and
/private/tmp/grt-builder-prototype-performance.md.
