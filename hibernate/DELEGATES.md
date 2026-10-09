# GRT-backed Hibernate delegates

This experimental, optional approach makes unchanged Viaduct generated runtime types (GRTs) the
application-facing values. Generated Hibernate entities hold their scalar state in a GRT and keep
native identity, association, and collection bookkeeping. Hibernate handles HQL/Criteria, SQL,
dirty checking, and transactions. The delegate execution path has no pg_graphql protocol, filter
DSL, JSON execution translation, or a generic entity-to-GRT translation layer.

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
existing relationship mappings, and generates `<MappingName>Entity` classes for GRT-backed mappings,
`<StorageName>Row` classes for pure storage rows, and
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
require the matching identity. Every supplied relationship object must include its target identity;
nullable relationships accept explicit null. Use `find` followed by `toBuilder()` so omitted mutation inputs
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
Selective reads query native Hibernate tuples containing row identity, owned scalar columns, and
the FK identities needed for requested relationships. They build GRTs directly, without hydrating
managed parent or child entities. Batch contexts with identical parent columns share a query while
retaining their own nested selections and result builders. Row identity is queried even when the
response does not select `id`, to check existence and assemble relationships; omitted GRT fields
remain unset. Each response uses a fresh typed builder. Relationships
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
    ).setParameter("name", "alice").resultList.map { it.grt().getUsername() }
}
```

Hibernate owns lazy proxies, associations, and persistent collection wrappers. Use exact generated
owning properties for writes, then reload inverse collections normally. Assigning an inverse
collection alone does not update its foreign keys. Selective Node collection reads query child IDs through
the mapped Hibernate relationship and return detached Viaduct references without initializing the
native collection or hydrating child entities. Hibernate flushes pending writes before those queries.
Ordinary object lists load their selected fields with a shared column projection before detaching.
Finite child selections bound traversal through ordinary-object cycles. GraphQL lists still return
every result; use modern connections to bound large results.
Each selected collection issues an ID query per parent/context. Repeated aliases or many parent
collections may repeat queries; batch-query throughput has not been measured.
Native entity queries, `find`, and selecting/projecting already managed entities still have normal
Hibernate hydration semantics. An output selection cannot undo the columns those queries loaded.
Connection callbacks should select UUIDs for Node-only pages to avoid entity hydration; callbacks
that select managed edge rows still hydrate those rows. Partial read results are never managed
entities and cannot be flushed as replacements for omitted database fields.
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
    entity.grt()
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

## Ordinary objects and stored edges

For an explicitly mapped non-Node object, use normal Hibernate queries and public selections:

```kotlin
val document = persistence.transaction(ctx) { session ->
    val entity = session.createSelectionQuery(
        "from Document d where d.id = :id", DocumentEntity::class.java,
    ).setParameter("id", documentUuid).singleResult
    persistence.project(ctx, session, entity, documentSelections)
}
```

`documentSelections` is Viaduct's `SelectionSet<Document>`. Nested ordinary object relationships
are projected only as far as its finite selections request, so cycles do not recursively copy the
stored graph. Results are detached before the session closes. A complete write snapshot contains
identity-only ordinary-object references; use projection for requested nested fields. Native
replacement uses `entity.assign(replacement, session)` and ordinary Hibernate dirty checking.
`find`/`update`/`delete` by GlobalID and selective Node resolvers remain Node-specific.

For `Group.members: People` with persisted fields on `PersonEdge`, query its association delegates:

```kotlin
persistence.fetchConnection(ctx, ctx.selections()) { session ->
    session.createSelectionQuery(
        "from GroupMembersAssociation e where e.owner.internalId = :group " +
            "order by e.internalId",
        GroupMembersAssociationEntity::class.java,
    ).setParameter("group", groupUuid)
}
```

The concrete edge GRT contains its node and selected persisted edge fields. Insert/update its native
entity inside the transaction and set its generated `owner` association through Hibernate; GraphQL
cursors are supplied when building the connection. If several storage mappings reuse an edge GRT,
choose the specific generated association binding instead of inferring a unique mapping from the
GRT class. Abstract connections accept their mapped edge rows or compatible concrete node entities
for plain edges. A UUID alone cannot identify the concrete type of an abstract target. Queries for
edges with persisted fields must return their association delegates.

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

Supported delegate shapes include mapped concrete objects, Node identities, interface/union Node
relationships, persisted custom edges, storage-only rows, scalar/enum/timestamp/offset-time fields,
standalone and paired `@idOf`, FK/join collections, synthesized owners, and modern connections. Scalar and enum lists use the existing PostgreSQL array mappings, preserving nullable
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

The delegate contract is `GrtEntity<T : viaduct.api.types.Object>`; `grt()` returns the concrete,
unchanged GRT. `NodeGrtEntity` and `NodeGrtBinding` add only Node identity/GlobalID behavior.
Interface/union fields use the concrete mapped delegates of their targets. Abstract list reads
project the existing concrete FK columns without hydrating target Nodes or initializing bags.
Single-member abstract types use the same tuple projection as mixed types.

Persisted custom connection edges have delegates named for their storage mapping, containing the
concrete edge GRT. Their owner and native UUID remain Hibernate state. Connections read those
edge delegates, preserve selected edge fields, and add cursors through Viaduct's normal builders.
A plain abstract list's synthetic reference rows remain native `<StorageName>Row` objects; they
have no invented GRT, Node identity, or GraphQL type.

Bridge state uses names reserved by GraphQL and methods without bean getter prefixes. Ordinary
schema fields such as `value`, `context`, `binding`, `builder`, `current`, `pending`, and
`native_label` are supported. Genuine JavaBean accessor collisions still fail generation.
The underlying persistence model's constraints still apply: automatic Gradle discovery selects
Nodes; standalone non-Node mappings must already be explicitly included in the persistence model
and have a schema `id` backed by the existing UUID primary key. Abstract persistent targets remain
Nodes under the shared model validator. This change does not alter root discovery, invent identities
for objects without an ID, or add support for previously rejected persistence model shapes.
Transactions continue to use native Hibernate commit/rollback.

Tests compile fresh real GRT bytecode and generated delegates, execute PostgreSQL CRUD and
relationships, and run the real Viaduct engine for selections, aliases, checkers, and errors.
They also cover modern connections, transaction failure/cancellation, request isolation, lock
timeout recovery, and physical-schema equivalence. All 69 affected runtime/generator/consumer
regressions were verified across final runs, including 18 object/edge tests and 28 Node integration
tests. Ordinary object lists, finite cyclic selections, independent associations sharing an edge
GRT, and rejection of supplied relationships without identities are covered.
Consumer build coverage verifies opt-in
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
