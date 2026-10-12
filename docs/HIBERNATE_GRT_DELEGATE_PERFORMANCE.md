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
