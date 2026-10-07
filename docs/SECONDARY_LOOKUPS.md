# Secondary lookups

`DbLookup<K, N>` gives an existing condition a reusable Kotlin name. It returns ordinary Viaduct node references through `DbClient.lookup`, or a generated modern Viaduct connection through `DbClient.lookupConnection`.

The descriptor does not create an index, change the database schema, add YAML configuration, or declare a public GraphQL field. Existing storage and indexes serve the query. Applications decide which GraphQL resolvers expose it.

Tracking: [ticket #35](https://github.com/viaduct-dev/pg-persistence/issues/35), [Slate design](https://slate.airbnb.tools/x4RnHcqR9q).

## Declare a lookup

Suppose the application already has these persisted shapes:

```graphql
type Person implements Node { id: ID!, name: String! }
type GroupMember implements Node { id: ID!, person: Person! }
type Group implements Node {
  id: ID!
  members(first: Int, after: String, last: Int, before: String): GroupMemberConnection!
    @resolver(isSelective: true)
}
type GroupMemberEdge @edge { cursor: String!, node: GroupMember! }
type GroupMemberConnection @connection {
  edges: [GroupMemberEdge!]!
  pageInfo: PageInfo!
}
```

Use generated reflection fields in an application Kotlin object:

```kotlin
import dev.viaduct.persistence.runtime.db.DbLookup
import viaduct.api.grts.Group
import viaduct.api.grts.GroupMember
import viaduct.api.grts.Person

object MembershipLookups {
    val byPerson = DbLookup.by(GroupMember.Fields.person)
    val byGroup = DbLookup.related(Group.Fields.members)
    val usersByGroup = byGroup.project(GroupMember.Fields.person)
    val peopleByName = DbLookup.by<String, Person>(Person.Fields.name)
}
```

`by` matches a scalar field or stored to-one foreign key. Node reference fields infer a typed `GlobalID` key. Scalar field reflection lacks the Kotlin value type, so scalar declarations supply explicit key and node type parameters. The generated `id` field maps to the provider's `uuidId`.

`related` follows an existing modern connection using its parent `GlobalID`. The existing schema translation chooses direct inverse foreign-key or association-table storage. The parent key is validated against the containing node type.

`project` follows one stored to-one reference from each matching source row. It preserves row count and order: two memberships for the same person return that person twice. No uniqueness constraint or automatic deduplication is involved. A missing or null projected foreign key fails instead of silently dropping a row.

Initial descriptors support concrete persisted node types, modern connection relationships, and one projection hop. They reject collection projection and chained projection. Interface/union references and plain list relationship descriptors are outside this API.

When the generated Hibernate mapping is on the node type's classpath, descriptors reject nonpersisted types and fields before making a database request. This uses the existing mapping resource and includes stored connection fields and `@idOf` aliases. Applications without that resource retain provider-side validation. Raw filter callbacks and ordering still use provider field names and provider validation.

Unique fields use the same list or connection API: matching a complete non-null unique key returns zero or one row. Matching only part of a composite key can return multiple rows. PostgreSQL unique constraints allow multiple nulls by default, so a null condition is not necessarily a unique lookup.

## Wrap a composite condition

Existing `PgGraphqlFilter` conditions, including `allOf` and `oneOf`, can be named without adding another filter language:

```kotlin
import dev.viaduct.persistence.runtime.db.PgGraphqlFilter
import viaduct.api.globalid.GlobalID

data class MemberKey(val people: List<GlobalID<Person>>, val label: String)

val membersByPeopleAndLabel = DbLookup.where<MemberKey, GroupMember>(GroupMember.Reflection) { key ->
    PgGraphqlFilter.allOf(
        PgGraphqlFilter.oneOf("personId", key.people),
        PgGraphqlFilter.eq("label", key.label),
    )
}
```

This composite example assumes the application also has a stored `GroupMember.label` field. `where` uses the existing provider filter names. Filter values are bound as GraphQL variables. Callbacks must be stateless and must not capture execution contexts, credentials, mutable request state, or blocking work.

## Execute from a resolver

```kotlin
// A list field resolver:
return dbClient.lookup(ctx, MembershipLookups.usersByGroup, ctx.arguments.groupId)

// A selective connection field resolver returning PersonConnection:
return dbClient.lookupConnection(ctx, MembershipLookups.usersByGroup, ctx.arguments.groupId)

// When the caller already holds a connection selection set:
return dbClient.lookupConnection(ctx, MembershipLookups.usersByGroup, groupId, selections)
```

The result connection must have the lookup's result node type. Declare the public list or connection field yourself and keep the normal node resolvers. References are hydrated and authorized through the application's existing Viaduct resolvers.

Both methods use the same database executor and per-request headers as existing `DbClient` reads. Database access rules apply to source rows, while target node authorization follows the application's normal resolver behavior. A lookup descriptor grants no access by itself. Upstream errors and coroutine cancellation propagate through the existing transport.

## Ordering and paging

Both methods accept the existing optional `orderBy: List<PgGraphqlOrder>`. Ordering applies to source rows, including association rows for an association-backed relationship. Keep the key and ordering stable across pages. Projecting a reference does not reorder by the target node.

`lookup` drains all provider pages and returns a list. Use `lookupConnection` for bounded results. Its only paging arguments and public cursors are Viaduct's OSS connection arguments and offset cursors; provider continuation cursors remain internal. It shares `ConnectionFetcher`'s count, slice, and generated-builder behavior, including provider row caps and backward paging. Separate requests do not share a database snapshot.

Custom result edge fields read from the source row for a root lookup, or from the existing logical edge for a relationship lookup. The requested field must exist at that storage location. Projection changes only the node reference; edge metadata remains attached to its source row.

Traversal checks cancellation between pages and fails on empty continuing pages, missing continuation cursors, or repeated cursors. It neither silently truncates matches nor loops indefinitely on that malformed metadata.

## Existing concrete association metadata

This change also corrects reversed GraphQL relationship names in the generated overlay for concrete associations and lets the existing connection reader find their storage response field. Regenerate and reapply the overlay for affected schemas. No table structure or index changes are needed. The OAuth sample's generated artifacts are unchanged because it does not use those association shapes.
