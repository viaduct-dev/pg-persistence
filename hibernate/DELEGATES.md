# GRT-backed Hibernate delegates

This experimental, optional approach makes unchanged Viaduct generated runtime types (GRTs) the
application-facing values. Generated Hibernate entities hold their scalar state in a GRT and keep
native identity, association, and collection bookkeeping. Hibernate handles HQL/Criteria, SQL,
dirty checking, and transactions. The delegate execution path has no pg_graphql protocol, filter
DSL, JSON execution translation, or recursive entity-to-GRT projection.

The existing `DbClient` and dynamic-map `HibernateClient` remain available. Gateloom and
[batteries-included](https://github.com/viaduct-dev/batteries-included) have not been migrated.
The delegate implementation is included in [PR #37](https://github.com/viaduct-dev/pg-persistence/pull/37)
on `feat/hibernate-grt-runtime`; it is not yet a published snapshot. Build that branch before enabling it.

## Generate delegates

In a Kotlin module already configured with Viaduct and the persistence plugin:

```kotlin
dependencies {
    implementation("dev.viaduct.persistence:hibernate:0.1.0-SNAPSHOT")
}

viaductPgPersistence {
    delegateGrtPackage.set("example.grts") // The package used by this module's Viaduct GRT codegen.
}
```

`generateViaductHibernateDelegates` reads the assembled schema and `pg-persistence.yaml`, reuses the
existing relationship mappings, and generates `<GRTName>Entity` classes and
`example.grts.persistence.PersistenceDelegates.bindings`. Sources go into
`build/generated/viaduct-grt-delegates/kotlin` and are added to Kotlin compilation. Viaduct continues
to generate the GRTs through its normal tasks. No GRT source or bytecode is edited. Removing the
option removes stale delegate sources on the next build.

Schema-only generation does not depend on compiling delegates. Generated HBM remains the same
physical-schema input. Runtime configuration changes only class/proxy representations; it does
not require a database migration. Native mode skips pg_graphql-specific field restrictions while
retaining persistence policy, relationship, and selective-node validation. Nonpersisted fields
still need application resolvers.

## Bootstrap Hibernate

Use the same naming strategies and metadata customizations used for schema generation. For the
default mappings, application startup can construct a factory as follows:

```kotlin
import dev.viaduct.persistence.hibernate.ViaductImplicitNamingStrategy
import dev.viaduct.persistence.hibernate.ViaductPhysicalNamingStrategy
import dev.viaduct.persistence.orm.grt.DelegateHibernateClient
import example.grts.persistence.PersistenceDelegates
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder

val registry = StandardServiceRegistryBuilder()
    .applySetting("hibernate.connection.datasource", applicationDataSource)
    .build()

val metadata = MetadataSources(registry)
    .addResource("META-INF/viaduct-persistence.hbm.xml")
    .metadataBuilder
    .applyImplicitNamingStrategy(ViaductImplicitNamingStrategy())
    .applyPhysicalNamingStrategy(ViaductPhysicalNamingStrategy())
    .build()

PersistenceDelegates.bindings.configure(metadata)
val factory = metadata.buildSessionFactory()
val persistence = DelegateHibernateClient(factory, PersistenceDelegates.bindings) { session, ctx ->
    configureTrustedDatabaseIdentity(session, ctx)
}
```

`applicationDataSource` and `configureTrustedDatabaseIdentity` are application code. The initializer
is required and runs after each transaction begins. Establish trusted roles/RLS claims and driver
or database timeouts there; an HTTP bearer token does not authorize a JDBC session automatically.
At application shutdown, close the factory and destroy the service registry. Destroy the registry
also if startup fails before the factory is created.

The binding configuration validates mapped bean accessors before changing metadata. A custom HBM
mapping must remain compatible with the generated entities; unsupported property overrides fail
startup rather than silently losing data. A factory configured for delegates must be used through
the delegate client's request-bound sessions.

## Work with GRTs

For a persisted `Person` node with a `username: String!` field:

```kotlin
val person = persistence.transaction(ctx) { session ->
    persistence.insert(ctx, session, Person.Builder(ctx).username("alice").build())
}

val renamed = persistence.transaction(ctx) { session ->
    val current = persistence.find(ctx, session, requireNotNull(person.getId()))
    persistence.update(ctx, session, current.toBuilder().username("bob").build())
}

persistence.transaction(ctx) { session ->
    persistence.delete(ctx, session, requireNotNull(renamed.getId()))
}
```

Supply every persisted scalar and owning to-one relationship, including explicit nulls. Updates
require the matching identity. Use `find` followed by `toBuilder()` so omitted mutation inputs
preserve existing values. A selective response is not a complete replacement. Validation runs
before changing managed state, and replacements never mutate previously returned GRTs.
Omission is detected through strict public generated getters and the public, stable
`UnsetFieldException`. Only that exception is caught; explicit nulls remain supplied values and
other getter failures propagate.

The transaction callback is synchronous, runs on `Dispatchers.IO`, and confines a Hibernate session
to one execution. Use that session for all steps; return GRTs or detached values. Commit follows
the callback, so do not publish its value externally before the transaction returns. Failures,
cancellation observed before commit, and fatal errors trigger rollback and propagate. Blocking
JDBC still requires driver/query/lock timeouts.

Selective node resolvers can use:

```kotlin
override suspend fun resolve(ctx: Context): Person = persistence.fetchNode(ctx)

override suspend fun batchResolve(contexts: List<Context>): Map<Context, FieldValue<Person>> =
    persistence.fetchNodes(contexts)
```

Batch contexts must belong to the same execution. Each response has its own field availability:
owned scalars, owned collections, and requested to-one references. Unselected fields remain unset.
Each response uses a fresh typed builder and copies only its selected field values. Relationships
contain detached lists or ordinary Viaduct node references. Results do not retain a Hibernate
entity/session. Missing nodes and ordinary per-node
selection failures become `FieldValue` errors; cancellation and fatal errors propagate. Database
loading failures fail the batch normally.

## Native relationships

Raw Hibernate queries return generated entities, not GRT classes:

```kotlin
val names = persistence.transaction(ctx) { session ->
    session.createSelectionQuery(
        "from Person p where p.username = :name order by p.internalId",
        PersonEntity::class.java,
    ).setParameter("name", "alice").resultList.map { it.value.getUsername() }
}
```

Hibernate owns lazy proxies, associations, and persistent collection wrappers. Use exact generated
owning properties for writes, then reload inverse collections normally. Assigning an inverse
collection alone does not update its foreign keys. Selective collection reads query child IDs through
the mapped Hibernate relationship and return detached Viaduct references without initializing the
native collection or hydrating child entities. Hibernate flushes pending writes before those queries.
GraphQL lists still return every reference; use modern connections to bound large results.
Each selected collection issues an ID query per parent/context. Repeated aliases or many parent
collections may repeat queries; batch-query throughput has not been measured.
Paired `manager`/`managerId @idOf` fields use the same owning association;
inconsistent replacements are rejected.

For a required unidirectional `Group.items` collection, its `ItemEntity` may have a synthesized
`groupId` owning property that is not a GraphQL field. Set it before native insertion:

```kotlin
val item = persistence.transaction(ctx) { session ->
    val entity = ItemEntity.BINDING.create(ctx) as ItemEntity
    entity.assign(Item.Builder(ctx).title("task").build(), session)
    entity.groupId = session.getReference("Group", UUID.fromString(groupId.internalID)) as GroupEntity
    session.persist("Item", entity)
    entity.value
}
```

Its property name comes from the existing HBM key mapping. This adds no GRT field or database
column. Keep generated entities inside the transaction. A `Group.members` association to a
membership entity does not infer `Group.users` through membership.person; use normal Hibernate
navigation or a join query for that path.

## Modern Viaduct connections

```kotlin
persistence.fetchConnection(ctx, ctx.selections()) { session ->
    session.createSelectionQuery(
        "select p.internalId from Person p order by p.username, p.internalId",
        UUID::class.java,
    )
}
```

Supply an unpaged query returning the connection node type's native UUIDs or generated entities,
with deterministic ordering and an identifier tie-breaker. An ID projection avoids hydrating full
entities; entity queries remain supported. Viaduct
validates connection arguments, computes bounds, supplies `OffsetCursor`, and constructs PageInfo
through the generated `fromEdges` builder. Hibernate supplies the ordered slice and a count when
needed. `fromEdges` is a published experimental Viaduct API; generated connection helpers opt into
`ExperimentalApi`. No additional public paging/cursor API exists. Offset paging has Viaduct's normal behavior
when data changes between requests; ordering and transaction isolation remain application choices.

## Current support and verification

The generated path is tested against Viaduct `2.1.0-20260922.061944-33` and Hibernate `7.3.4.Final`.
Production delegates use public typed GRT getters/builders, resolver contexts, GlobalID serialization,
node references, and selection sets. They do not access Viaduct internal constructors, backing data,
or engine selections. Published experimental reflection/connection APIs are explicitly opted into.
Other releases still require compatibility verification.

Request-bound construction uses Hibernate's supported per-session `Interceptor.instantiate`
extension. Its signature requires `EntityRepresentationStrategy`; the bridge does not inspect or
call it. A private wrapper delegates to the public `Session` API to check client/request ownership
without inspecting Hibernate implementation objects or keeping shared request state.

Supported shapes include concrete Node entities, scalar/enum/timestamp/offset-time fields, owning references,
standalone and paired `@idOf`, FK/join collections, synthesized owners, and plain modern
connections. Scalar and enum lists use the existing PostgreSQL array mappings, preserving nullable
elements, empty lists, and nullable lists. JSON scalar fields use Hibernate's existing JSON column
mapping with public GRT `Any` getters/builders; this is column persistence, not a JSON execution
protocol. Untyped ID fields use the existing UUID database convention and require valid UUID
strings. `isX` fields use Viaduct's public getter naming and explicit Hibernate bean methods.
Timestamp arrays compare instants so equivalent JDBC offsets cannot trigger unnecessary updates.
JSON lists use native PostgreSQL `jsonb[]` columns and Hibernate's JSON element type, preserving
structured objects, nested arrays, primitive values, nullable elements, empty lists, and null lists.
`Time` uses `OffsetTime` and `time(6) with time zone`; Time lists use `timetz[]`. The JDBC adapters
preserve offsets and microseconds and use Hibernate's normal element conversions and dirty checking.
There is no JSON execution protocol. An existing `time without time zone` column needs a migration
to preserve offsets; regenerate the schema and choose how existing offset-less values should be interpreted.

Generation still rejects non-Node entities, abstract relationships, custom persisted edges, and
bridge-state/accessor name collisions. These are limitations of the current bridge, not GRTs or
Hibernate. Supporting abstract relationships and persisted edge fields requires mapping native
storage/association rows separately from the GraphQL values they represent. There is no automatic
provider fallback. Transactions use native Hibernate commit/rollback rather than buffered requests.

Tests compile fresh real GRT bytecode and generated delegates, execute PostgreSQL CRUD and
relationships, and run the real Viaduct engine for selections, aliases, checkers, and errors.
They also cover modern connections, transaction failure/cancellation, request isolation, lock
timeout recovery, and physical-schema equivalence. Consumer build coverage verifies opt-in
compilation, unchanged mappings/GRT bytecode, and stale-source removal. These checks do not certify
live consumer migrations or production RLS policy behavior. See the
[design and verification record](../docs/HIBERNATE_GRT_DELEGATE_DESIGN.md) for results and scope.

## Measure collection and connection reads

With the local PostgreSQL integration-test environment configured, run:

```sh
./gradlew :plugin:delegatePerformanceTest --no-parallel
```

This opt-in task compares native entity hydration with ID projections against the same 5,000-row
fixture. It records latency, allocations on the IO thread, entity/collection loads, and SQL counts
in `plugin/build/reports/hibernate-delegates/performance.md`. Compilation and setup are excluded;
each measured read uses a fresh session. Timing is reported without a machine-specific assertion.
The normal regression suite checks cardinality, detached values, zero child hydration, and modern
forward/backward paging independently. These local measurements do not establish production latency.
See the [recorded local measurements](../docs/HIBERNATE_GRT_DELEGATE_PERFORMANCE.md).
