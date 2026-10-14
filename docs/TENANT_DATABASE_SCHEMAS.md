# Tenant PostgreSQL schemas — KAN-37

Tracking issue: [KAN-37](https://viaduct-dev.atlassian.net/browse/KAN-37).

Implemented in [PR #40](https://github.com/viaduct-dev/pg-persistence/pull/40), based on
[PR #36](https://github.com/viaduct-dev/pg-persistence/pull/36). This feature uses the
existing `DbClient` and pg_graphql runtime. It does not depend on the experimental
native Hibernate/GRT runtime in PR #37. Existing Hibernate build-time schema tooling
continues to generate metadata, DDL, and migration diffs.

## Current implementation

The transport increment supplies `JdbcSchemaVisibility` and a real PostgreSQL/pg_graphql transport
feasibility regression, also exercised through PostgREST. It establishes that one GraphQL
document can traverse foreign keys and association rows across schemas in one database, and
that mixed-schema mutations commit or roll back atomically.

Module placement is now supported in `src/main/viaduct/pg-persistence.yaml`:

```yaml
databaseSchema: orders
types:
  Order:
    semanticNotNull: true
```

The schema is explicit; it is never inferred from the module package or tenant name. Omission
keeps public placement, and several modules may deliberately share a schema. Names must match
`[a-z_][a-z0-9_]{0,62}` and cannot start with `pg_`. Placement is a module policy; an assembled
application cannot assign one schema to all incoming modules.

Each module publishes tracked ownership and policy metadata with its Viaduct schema partition.
Apply `dev.viaduct.pg-persistence` to the owning modules and the application that assembles them.
Central assembly aggregates those artifacts without compiling resolvers. Only original object
definitions claim ownership: references and extensions keep the defining module's schema.
Conflicting ownership, schema placement, or semantic policies fail generation. Module semantic
policies are aggregated so central generation retains exclusions, non-null rules, unique keys,
relationship overrides, secondary indexes, and retryable transaction settings.

Placement survives semantic-model serialization and HBM generation. Canonical owners determine
reciprocal and nested association placement, and Hibernate qualifies FK endpoints. Fresh SQL
creates required application schemas; Liquibase snapshots and diffs include mapped schemas.
Public GraphQL names stay unchanged. Placement does not grant privileges or configure the
runtime executor's schema visibility automatically.

PostgreSQL diff generation refuses to treat a missing destination table as new when the same
physical name exists in another non-system schema. Apply an explicit reviewed data-preserving
migration first, such as `ALTER TABLE ... SET SCHEMA ...`, then regenerate the diff. This guard
is deliberately conservative: it cannot infer whether a same-named table is unrelated, and it
does not author, approve, or apply migrations.

## Shared visibility contract

The application supplies the entire authorized schema set for a transaction. Every resolver
and nested relationship in that transaction uses that set; the executing tenant does not
switch the connection to its own schema. PostgreSQL privileges and RLS still enforce access.
Putting a schema in `search_path` does not grant `USAGE` or table access.

For JDBC, configure a restricted application DataSource and opt in explicitly:

```kotlin
val executor = JdbcPgGraphqlExecutor(
    dataSource,
    JdbcSchemaVisibility(listOf("public", "people", "orders")),
)
val dbClient = DbClient(executor)
```

The helper copies and deduplicates the names, sorts them deterministically, quotes each
identifier, and binds the complete search path to `set_config('search_path', ?, true)`.
It rejects implicit `$user`/`pg_temp` tokens, empty sets, blank/NUL-containing names, and names
that exceed PostgreSQL's 63-byte identifier limit. It does not add `public` implicitly.
PostgreSQL still searches its implicit system schemas according to its normal rules.
Application schemas must have distinct public GraphQL names regardless of search-path order.

By default, headers are rejected. A trusted role/JWT setup callback can be passed as the second
argument to `JdbcSchemaVisibility`; it runs before the schema setting, on the same connection.
The helper requires an open transaction. It never commits, rolls back, or closes the connection.
Transaction-local settings disappear when the owner commits or rolls back, including on
connections returned to a pool. Caller-owned connections retain the settings until their
transaction ends, following the existing JDBC ownership contract.

For HTTP, the gateway must apply the equivalent schema set and database identity **before**
pg_graphql executes, using trusted transaction-local request setup. The HTTP executor does not
interpret a client-supplied schema header as authorization. The fixture demonstrates this with
a PostgREST pre-request hook and an invoker-rights RPC wrapper around `graphql.resolve`.
The fixture uses the same restricted role for HTTP and JDBC.

## Transport feasibility coverage

`CrossSchemaFeasibilityTest` provisions two schemas in the guarded local `kan23_jdbc_tests`
database, plus restricted fixture roles. A people `account` is referenced by an orders
`purchase`; an orders association row links the purchase to a reviewer in people. Both
schemas also contain a physical table named `account`, with distinct GraphQL names.

The transport cases verify:

- One HTTP/JDBC document traverses the purchase, buyer, association row, and reviewer.
- Identically named physical tables remain distinguishable through stable GraphQL names.
- Input schema order does not change the result.
- HTTP/JDBC mixed-schema writes commit together.
- A late duplicate-key failure rolls back writes in both schemas.
- Omitting a schema removes its collection from the GraphQL surface.
- Missing schema privileges prevent exposure even when the schema is in the search path.
- Default public visibility does not implicitly expose tenant schemas.
- A borrowed connection restores its previous role and search path after rollback.

Unit regressions also cover bound identifier quoting, defensive copying, invalid schema names,
header rejection, and autocommit rejection. CI configures PostgREST and sets the HTTP endpoint
so these live HTTP cases run instead of being skipped. Test task inputs include the endpoint,
database URL, and provisioning user, preventing reuse of a JDBC-only result for an HTTP run.

Local transport validation on 2026-10-14: all 50 JDBC tests passed with zero failures, errors,
or skips, including all 13 cross-schema transport/pool cases and the schema-visibility unit
cases. Ktlint, standard Detekt, and main/test SpotBugs passed. The live fixture used PostgreSQL
17.6, pg_graphql 1.5.11, PostgREST 14.12, and HikariCP 7.1.0. The updated GitHub workflow has
not been run remotely.

This fixture is a **transport subgate** and uses hand-authored SQL. The placement regressions
below connect real module assembly, generated qualified DDL, and public Viaduct execution.
The transaction and connection regressions also cover mixed-schema DBOS/retryable operations,
cancellation, concurrent requests, and reuse through a real HikariCP pool.

## Generated placement coverage

`SelectiveNodePluginTest` assembles two real Viaduct modules with cross-module references,
generates central HBM without compiling resolvers, checks unchanged inputs are up to date,
and verifies that changing one module's schema regenerates its placement.
Its full application consumer applies the normal Viaduct application/module plugins to two
independent tenants. It compiles real GRTs and resolver bases, installs the generated schema
in PostgreSQL, and runs `BasicViaductFactory` queries and mutations through `DbClient` and
`JdbcPgGraphqlExecutor`. Buffered mutations span both schemas; both tenants use existing
selective Node resolvers. Named lookups return the expected edges, cursors, and nested
cross-tenant data. A late duplicate-key mutation rolls back both schemas, and ordinary
global Node lookup remains unchanged. No generated Hibernate delegates or native Session
runtime are required.

`PersistencePlacementTest` covers original ownership through references/extensions, qualified
Hibernate FK endpoints, canonical reciprocal and nested associations, shared/public schemas,
metadata order, serialization, and conflict rejection. `PersistenceModulePoliciesTest` covers
central policy aggregation and conflicting policies.

`TenantPlacementIntegrationTest` executes generated HBM, fresh Hibernate SQL, and PostgreSQL/
pg_graphql overlays against an explicitly selected disposable database. It checks cross-schema
FK and association traversal and data retention
after an explicit table move. Its snapshot/diff case deliberately gives two types the same
physical table name in different schemas, including a declared unique key. The snapshot retains both qualified identities;
unchanged mappings produce no structural migration, and restoring a missing column changes
only its owning schema. `PlacementIdentityValidationTest` rejects physical collisions within
one schema and public GraphQL collection collisions across schemas.

Liquibase's stock Hibernate adapter flattens schemas and resolves tables by unqualified name.
The qualified snapshot generators preserve schema names and resolve columns, indexes, primary
keys, unique constraints, and FK endpoints against the qualified mapping. They retain desired
pg_graphql table and association comments, and PostgreSQL FK naming/actions, so these details
do not cause spurious structural changes. Existing public-only consumers retain their original
snapshot behavior.

The live association uses the established edge-backed pattern,
which generates a primary key. Plain Hibernate bag join tables lack a primary key and are not
exposed by pg_graphql; their reciprocal placement is checked separately.

After starting the disposable cluster below, run:

```bash
podman exec pg-persistence-kan37-db psql -U postgres -v ON_ERROR_STOP=1 \
  -c 'CREATE DATABASE kan37_generated_tests'
podman exec pg-persistence-kan37-db psql -U postgres -d kan37_generated_tests \
  -v ON_ERROR_STOP=1 -c 'CREATE EXTENSION pg_graphql'
PG_KAN37_GENERATED_URL=jdbc:postgresql://127.0.0.1:55326/kan37_generated_tests \
  ./gradlew :plugin:test --tests '*TenantPlacementIntegrationTest' \
    --tests '*PersistencePlacementTest' --tests '*PersistenceModulePoliciesTest' \
    :plugin:selectiveNodePluginValidationTest --no-daemon --no-parallel
```

The generated live test requires a local `kan37_generated_tests` database and skips when its
URL is omitted. CI provisions it and tracks the URL as a test input.
The fixture password defaults to `postgres`; override it with `PG_KAN37_GENERATED_PASSWORD`.

## Run locally in a disposable cluster

Use a dedicated PostgreSQL instance: fixture roles are cluster-wide. The provisioning user
defaults to `supabase_admin` because Supabase's `postgres` role cannot grant access to the
extension-owned `graphql` schema. Override `PG_JDBC_TEST_ADMIN_USER` for other local installations.
The application execution in this fixture always switches to a restricted role.

```bash
podman network create pg-persistence-kan37
podman run -d --name pg-persistence-kan37-db --network pg-persistence-kan37 \
  -p 127.0.0.1:55326:5432 -e POSTGRES_PASSWORD=postgres \
  public.ecr.aws/supabase/postgres:17.6.1.132

# Wait until this reports that PostgreSQL accepts connections.
podman exec pg-persistence-kan37-db pg_isready -U postgres
podman exec pg-persistence-kan37-db psql -U postgres -v ON_ERROR_STOP=1 \
  -c 'CREATE DATABASE kan23_jdbc_tests'
podman exec pg-persistence-kan37-db psql -U postgres -d kan23_jdbc_tests \
  -v ON_ERROR_STOP=1 -c 'CREATE EXTENSION pg_graphql'

podman run -d --name pg-persistence-kan37-http --network pg-persistence-kan37 \
  -p 127.0.0.1:55325:3000 \
  -e PGRST_DB_URI=postgres://postgres:postgres@pg-persistence-kan37-db:5432/kan23_jdbc_tests \
  -e PGRST_DB_SCHEMAS=public -e PGRST_DB_ANON_ROLE=kan37_reader \
  -e PGRST_DB_PRE_REQUEST=public.kan37_request_setup \
  public.ecr.aws/supabase/postgrest:v14.12

PG_JDBC_TEST_URL=jdbc:postgresql://127.0.0.1:55326/kan23_jdbc_tests \
PG_CROSS_SCHEMA_HTTP_URL=http://127.0.0.1:55325/rpc/kan37_graphql \
  ./gradlew :jdbc:check --no-daemon --no-parallel
```

The fixture creates and removes its schemas, roles, and RPC functions. It fails rather than
overwriting existing fixture objects. Its database URL and HTTP endpoint must be local and
match the fixture's database/route. Without `PG_CROSS_SCHEMA_HTTP_URL`, only the three HTTP
cases skip. PostgreSQL is required for the JDBC cases.

After testing, remove only these disposable resources:

```bash
podman rm -f pg-persistence-kan37-http pg-persistence-kan37-db
podman network rm pg-persistence-kan37
```

## Transactions and pooled connections

`CrossSchemaRetryableTransactionTest` generates two placed types and executes the existing
retryable transaction API against PostgreSQL. It verifies atomic rollback of a late buffered
failure, recovery after a lost response without repeating application work, and concurrent
resume of one operation with exactly one committed pair of rows and one durable record.

The real DBOS compatibility suite uses a second application schema and `JdbcSchemaVisibility`
for the whole transaction. Mixed-schema cases verify commit, late mutation failure, cancellation,
commit acknowledgement loss, and durable replay after restarting DBOS. Replay preserves later
changes in both schemas instead of repeating the writes.

`CrossSchemaFeasibilityTest` also uses HikariCP. A one-connection pool proves the same physical
PostgreSQL connection restores its role and search path after success, mutation failure,
restricted access, and cancellation. Concurrent requests through a two-connection pool retain
independent schema sets and roles.

These tests use application-supplied visibility and database privileges. Schema placement does
not authorize callers or change database roles. The feature remains opt-in, within one database;
existing tables still require an explicit reviewed data-preserving move.

## Verification

Run the standard `build` task against the disposable cluster, setting the database URLs
shown above and the existing retry/DBOS integration URLs. It runs library tests, Gradle
consumer regressions, formatting, Detekt, SpotBugs, and Gradle plugin validation. The
source guard rejects explicit broad failure catches and broad Result helpers in owned
sources. Three runtime HTTP transaction tests require separate API credentials.

The feature remains opt-in, within one database. This change does not deploy an application
or apply a data migration.

Local verification on 2026-10-14: `./gradlew build --no-parallel` passed on the
supported #36 base: 879 passing tests and 3 credential-gated runtime HTTP skips.
All KAN-37 cases ran, including the two-module `DbClient` consumer and cross-schema
secondary-index snapshot/diff coverage. Formatting, Detekt, SpotBugs, plugin validation,
and the broad-catch source guard passed.
