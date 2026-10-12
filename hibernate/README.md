# Native Hibernate bridge for Viaduct

For the optional GRT-facing approach, see [GRT-backed Hibernate delegates](DELEGATES.md).
It generates native Hibernate entities that hold unchanged Viaduct GRTs, supports GRT CRUD and
modern connections, and avoids the dynamic-map projection described below. Both approaches remain
available; the delegate implementation currently supports fewer schema shapes.

This optional module uses the schema-generated Hibernate mappings directly. Applications write
ordinary Hibernate HQL/Criteria queries and use `Session.persist`, managed entity changes, and
`Session.remove`. The bridge supplies a transaction boundary, selected Viaduct GRT snapshots,
batched node loading, and modern Viaduct connections. It does not implement the pg_graphql
`DbClient` API or a separate filter language.

The application owns its `SessionFactory`, bootstrapped from the generated
`META-INF/viaduct-persistence.hbm.xml` with the same configuration and naming strategies used by
schema generation. This module packages those naming strategies and registers native SQL array
and JSON column types with Hibernate. Regenerate mappings with this branch before using it.
Generated UUID identifiers use Hibernate's `uuid2` generator; SQL column definitions are unchanged.

## Transactions and queries

```kotlin
val persistence = HibernateClient(sessionFactory) { session, context ->
    // Application code: establish trusted request identity, roles, and RLS claims here.
    configureTrustedDatabaseIdentity(session, context)
}

val matchingIds = persistence.transaction(ctx) { session ->
    session.createSelectionQuery(
        "select p.internalId from Person p where p.username = :username",
        java.util.UUID::class.java,
    ).setParameter("username", "alice").resultList
}
```

`configureTrustedDatabaseIdentity` represents application code, not a library function. The
initializer is required and runs after the transaction begins. A Supabase bearer token does not
automatically authorize a JDBC session. Applications can use `Session.doWork` for SQL functions
and transaction-local database settings.

The transaction block is synchronous and runs on `Dispatchers.IO`. Hibernate owns commit,
rollback, and session closure. Cancellation is checked before work and before commit. Blocking
SQL still uses normal JDBC/query timeouts; coroutine cancellation cannot interrupt a blocked
driver call. Hibernate exceptions propagate without pg_graphql error translation. Return detached
values or GRT snapshots; do not return a session or a lazy managed object. Pass the same session
to helpers for a multi-step transaction rather than starting another transaction from its block.

## Node resolvers and selected results

```kotlin
override suspend fun resolve(ctx: Context): Person = persistence.fetchNode(ctx)

override suspend fun batchResolve(contexts: List<Context>): Map<Context, FieldValue<Group>> =
    persistence.fetchNodes(contexts)
```

Batch contexts belong to one Viaduct execution. Hibernate loads identifiers in batches; the bridge
builds each result with that context's owned selections and requested relationship references.
Missing rows produce per-context `FieldValue` errors. Ordinary projection failures remain local to
that result; cancellation and fatal errors propagate. Hibernate/database loading failures fail the
batch normally.

`persistence.project(ctx, session, entity, selections)` builds a detached result inside an
application transaction. Generated GRT classes stay unchanged. Hibernate manages dynamic-map
entities; generated builders construct read snapshots before the session closes. Scalar adaptation
at this boundary covers arrays to lists, enum names to generated enums, and timestamps to
Viaduct `Instant`. Hibernate handles actual JSON columns; there is no JSON request/response protocol.
Nonpersisted fields require application resolvers.

## Writes

```kotlin
val itemSelections = ctx.selections().selectionSetFor(ChecklistItemPayload.Fields.checklistItem)
val item = persistence.transaction(ctx) { session ->
    val input = ctx.arguments.input
    val group = session.getReference("Group", java.util.UUID.fromString(input.groupId.internalID))
    val now = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
    val entity = mutableMapOf<String, Any?>(
        "title" to input.title,
        "completed" to false,
        "groupId" to group, // Exact generated mapping name for @idOf(type: "Group").
        "userId" to currentUserId, // Application's authenticated caller.
        "createdAt" to now,
        "updatedAt" to now,
    )
    session.persist("ChecklistItem", entity)
    persistence.project(ctx, session, entity, itemSelections)
}
```

Use native mapped values and exact mapping names, not pg_graphql aliases such as `uuidId` or
inferred `managerId` aliases for an object field named `manager`. Map a GraphQL global ID to a
Hibernate reference explicitly. For updates, change the loaded entity's supplied fields and let
Hibernate dirty checking write them; preserve omitted inputs and apply explicit nulls deliberately.
Use `Session.remove` for deletion. Collection ownership follows the generated Hibernate mapping. For a collection without a GraphQL
inverse, the generated target mapping contains a parent association named after its key column
(for example `GroupMember.groupId`). Assign that association to the managed parent; query the
inverse collection normally. This adds no GraphQL field or database column.
The bridge does not add mutation handles, retries, durable exports, or another transaction API.

## Modern Viaduct connections

```kotlin
persistence.fetchConnection(ctx, ctx.selections()) { session ->
    session.createSelectionQuery(
        "from Group g where g.status = :status order by g.name, g.internalId",
        Any::class.java,
    ).setParameter("status", "ACTIVE")
}
```

Supply an unpaged entity query with deterministic ordering, including a unique tie-breaker.
Relationship resolvers use the same method with a normal join or association predicate. Viaduct's
connection arguments compute bounds, `OffsetCursor` supplies cursors, and the generated
`fromEdges` builder constructs PageInfo. Hibernate supplies the slice and, when backward paging
requires it, the count. There is no additional public page/cursor interface. Resolve custom edge
fields separately. Generic GRT projection rejects connection paging.

Gateloom and batteries-included remain on their existing provider in this branch. The focused
PostgreSQL tests exercise their membership, checklist, query, and connection patterns; this is not
an end-to-end migration of either app. See [verification and scope](../docs/HIBERNATE_RUNTIME_EXPERIMENT.md).
