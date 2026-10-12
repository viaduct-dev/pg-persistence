# Secondary lookups

`DbLookup<K, N>` lets applications declare a database lookup once rather than repeatedly assembling filters and relationship queries in their resolvers. Choose matching rows with `by`, `related`, or `where`; optionally follow a stored reference with `project`; then execute the descriptor through `DbClient.lookup` for a list of Viaduct node references or `DbClient.lookupConnection` for a modern Viaduct connection.

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

## From a descriptor to a result

Assume the application schema already defines a persisted `Person.username` field:

```kotlin
val byUsername = DbLookup.by<String, Person>(Person.Fields.username)
val matches = dbClient.lookup(ctx, byUsername, "alice")
```

1. Declaring `byUsername` records the source type and equality condition. It checks the descriptor's supported shape and available persistence mapping, without querying the database.
2. Calling `lookup` supplies the execution context and key. The planner binds the key in the `lookupFilter` query variable, equivalent to `{"username":{"eq":"alice"}}`.
3. The existing database executor runs the filtered query with the invocation's normal request headers. The list API follows internal provider continuation cursors until every matching row has been read.
4. Each stored node ID becomes a normal Viaduct node reference. The application's node resolvers and authorization checks handle its requested fields.

`lookupConnection` uses the same lookup condition and the existing connection fetcher. It reads Viaduct's arguments from the resolver context, translates them into database slices, and builds the generated connection with Viaduct offset cursors. A backward page performs a count traversal when Viaduct needs the total. Provider cursors stay internal.

For `DbLookup.related(Group.Fields.members).project(GroupMember.Fields.person)`, the planner first locates the group using its typed `GlobalID`, follows the existing membership relationship, and reads each membership's stored person ID. Two matching memberships for the same person produce two references or edges. Projection preserves source-row positions and edge metadata.

## What uniqueness changes

An existing persistence policy can constrain the stored field:

```yaml
types:
  Person:
    unique: [[username]]
```

Uniqueness is enforced by the generated PostgreSQL constraint. The lookup runtime uses the same descriptor, filter, and result API; it does not choose a separate unique mode or convert the result into a single object.

| Lookup condition | Expected result |
|---|---|
| Complete non-null unique key | Zero or one row |
| Part of a composite unique key | Zero or more rows |
| Null condition on a nullable unique column | Zero or more rows; PostgreSQL permits multiple nulls by default |

A null condition uses the existing `PgGraphqlFilter.isNull` filter. The live tests verify these cases through ordinary lists and forward/backward connections, and verify that the database actually rejects duplicate non-null keys.

## How early persistence validation works

`PersistedFieldMappings` reads `META-INF/viaduct-persistence.hbm.xml`, which the plugin already generates and packages. It builds a map from each Hibernate `entity-name` to its mapped property and relationship names. Generated primary IDs and stored `@idOf` aliases are recognized too; a connection field can remain valid even though it has a resolver.

`DbLookup.by`, `related`, and `project` check their selected fields against that map. Source and target node types are checked as applicable; `where` checks its source type. An excluded type or unmapped field fails when the descriptor is declared, before a provider request. For example, an absent `Person.displayName` field reports `Lookup field Person.displayName is not persisted`.

Schema generation already rejects resolver-only fields on persisted nodes. These runtime checks additionally catch excluded types and discrepancies between generated node descriptors and the packaged mapping. They validate packaged metadata; they do not inspect whether the live database has had its migrations applied.

The cache is scoped to the generated type's classloader and uses weak keys. Cache access is synchronized briefly, while XML parsing occurs outside the lock. Cached values contain names, with no execution context or classloader references. External XML resources are disabled so custom mapping doctypes do not trigger external reads.

If the mapping resource is absent, the runtime keeps provider-side validation. Raw `where` filter field names and `orderBy` expressions also remain provider-validated. This adds no schema directive, YAML setting, generated artifact, or public paging API.

## Existing concrete association metadata

This change also corrects reversed GraphQL relationship names in the generated overlay for concrete associations and lets the existing connection reader find their storage response field. Regenerate and reapply the overlay for affected schemas. No table structure or index changes are needed. The OAuth sample's generated artifacts are unchanged because it does not use those association shapes.
