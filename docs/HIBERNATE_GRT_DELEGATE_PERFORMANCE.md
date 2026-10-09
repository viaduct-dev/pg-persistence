# Local Hibernate delegate measurements

PostgreSQL; 5,000 related Person rows; 100-row forward connection; Java 21.0.2.
Three warmup rounds and 20 alternating-order samples per case. Each sample opens, initializes,
reads, commits, and closes a fresh session against the same data. Setup and compilation are excluded.
SQL statements include two transaction-local timeout settings. Allocation is measured on the IO
thread with the JDK ThreadMXBean; it excludes server allocation and unrelated threads.

| Operation | Median ms | p95 ms | Median allocated KiB | Entities loaded | Collections loaded | SQL statements |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Collection: entity hydration | 53.34 | 75.51 | 69685.70 | 5001 | 1 | 4 |
| Collection: ID projection | 17.67 | 26.68 | 17989.80 | 1 | 0 | 4 |
| Connection: entity hydration | 5.78 | 20.51 | 1507.98 | 101 | 0 | 3 |
| Connection: ID projection | 3.98 | 7.34 | 464.75 | 0 | 0 | 3 |

These are local exploratory measurements using Hibernate's test connection pool, small scalar
payloads, and warm database caches. They do not establish production throughput or latency.
Lists still materialize every reference. Connections fetch the requested page plus one row.
