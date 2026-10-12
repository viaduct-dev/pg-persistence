# Native Hibernate runtime experiment

## Scope

Branch `feat/hibernate-grt-runtime` is stacked on PR #36 (`feat/named-lookups`), commit
`2b83ddd0fdf8fd29cbd17727d125291d5962b28f`.

The runtime uses Hibernate directly rather than reproducing every pg_graphql API capability.
`HibernateClient` provides synchronous native session transactions, node reads, batch reads,
selected GRT results, and modern Viaduct connections. Applications use Hibernate HQL/Criteria,
`persist`, dirty checking, `remove`, and `doWork` themselves. The existing pg_graphql runtime
continues to serve its existing consumers.

The implementation has no `OrmFilter`, `OrmMetadata`, `OrmMutations`, or `OrmSession`, and no
structured query/mutation backend protocol, JSON execution envelopes, recovery ledger adapter,
private transaction workers, or coroutine session-tracking element. The shared `GrtProjection`
and `GrtConnection` boundary only constructs Viaduct results. `HibernateObject` reads exact
mapped properties using Hibernate metadata; it does not infer aliases from physical columns
or interpret synthetic back-reference names.

Hibernate manages the generated dynamic-map entities. Unchanged Viaduct builders create detached
GRT snapshots while the session is open. The only scalar adjustments are array/list, generated
enum, and timestamp representations. Actual PostgreSQL JSON columns use Hibernate's standard
JSON mapping; no database operation is serialized through JSON.

The generated mappings register native array/JSON column types and Hibernate UUID generation.
Naming strategies are packaged with the runtime. Non-UUID identifiers keep assigned generation.
SQL generation and pg_graphql overlays remain available for existing consumers.

## Lifecycle and paging

The application supplies a required session initializer to establish trusted execution identity
and transaction-local roles/RLS settings. It runs inside every transaction. Hibernate owns
transaction completion and session closure, including rollback on failures. The synchronous
callback prevents a coroutine suspension boundary from being introduced into managed session
work. Application helpers share its session rather than open nested transactions.

Cancellation is checked before work and before commit. Hibernate may wrap fatal exceptions;
its normal exceptions propagate without provider error translation. SQL/query timeouts govern
blocking database work. No application session is shared between concurrent resolver calls.

Each batch context retains its own owned/requested selections. Missing rows and ordinary GRT
projection failures become per-context `FieldValue` errors; fatal failures and cancellation
propagate. Normal database/session failures fail the batch.

Connections require an unpaged native entity query ordered deterministically by the application.
The client uses only Viaduct's connection context, argument validation, offset conversion,
`OffsetCursor`, and generated `fromEdges` builder. Hibernate supplies the count and slice. There
is no alternative public pagination DTO or cursor protocol. Custom edge fields need their own
resolvers; generic projection cannot be used to bypass connection paging.

## Verification

Focused integrations create isolated PostgreSQL tables from generated Hibernate metadata and
compile real, unchanged Viaduct GRTs. Comparisons execute actual `graphql.resolve` calls on the
same local database, rather than mock its responses. Engine tests run GraphQL aliases, nested
references, checker denials, missing nodes, sibling data, and non-null bubbling.

Native tests cover membership joins and owning collections, checklist CRUD and `@idOf` fields,
input omission versus explicit null, bound HQL search predicates, scalar arrays/JSON/decimal/time,
batched reads with differing selections, detached snapshots, concurrent resolvers, transaction
rollback on ordinary/constraint/fatal failures and cancellation, and transaction-local RLS.
Connection comparisons cover forward/backward/default/empty pages and cursor boundaries.

Passed `:runtime:check :jdbc:check :dbos:check :hibernate:check :plugin:check`, including formatting,
Detekt, SpotBugs, plugin validation, and isolated generated-consumer checks.

| Check | Result |
| --- | --- |
| Plugin tests | 370 passed, including 29 native Hibernate/GraphQL/RLS cases |
| Selective resolver consumer tests | 2 execution and 3 validation cases passed |
| Runtime tests | 366 cases, no failures; 3 optional HTTP fixtures skipped |
| JDBC tests | 30 passed |
| OAuth sample clean regeneration | All 13 artifacts regenerated identically |
| OAuth sample fresh database | 29 tests passed, no skips |
| Generated sample SQL vs prior baseline | All 9 files identical |

No application SQL or Liquibase files were manually changed. The sample remains on pg_graphql;
its regeneration verifies that the generator change preserves existing consumers. Earlier broad
`DbClient` compatibility results do not certify this narrower native API.

## Limits

The integration schemas represent Gateloom and batteries-included patterns; neither application
is switched to Hibernate here. Their auth-specific SQL functions can use native JDBC work, but
production token exchange and a full app migration are outside this branch's verification.
Input-to-entity mapping is explicit application code, not an autogenerated mutation repository.
The [mutation repository slate](HIBERNATE_GRT_MUTATION_REPOSITORY_SLATE.md) remains a proposal.

Application code owns query semantics, deterministic ordering, relationship ownership, and
field-presence handling. A GRT is a result snapshot, not a mutable Hibernate entity. Native JDBC
values and exceptions follow Hibernate behavior rather than pg_graphql encoding/error wrappers.
