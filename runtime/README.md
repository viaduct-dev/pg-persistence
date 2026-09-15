# Runtime

The runtime sends pg_graphql requests for the fields in the Viaduct `SelectionSet` passed to
`DbClient`. It uses the reflection metadata generated with the GRTs to match Viaduct types and
fields to pg_graphql types and fields. It does not read a separate mapping file at runtime.

```kotlin
dependencies {
    implementation("dev.viaduct.persistence:runtime:0.1.0-SNAPSHOT")
}
```

```kotlin
val client = DbClient(
    httpClient = httpClient,
    endpoint = "$postgresGraphqlEndpoint/graphql",
    requestHeaders = DbRequestHeaders { context ->
        mapOf("Authorization" to "Bearer ${accessTokenFor(context)}")
    },
)
```

`DbClient` provides:

- `fetch` for a `DbRead` and `SelectionSet`. Shortcut for `fetchJson` + `toGRT`.
- `fetchResult` for a `DbResult` containing a GRT and any GraphQL errors returned by pg_graphql.
- `fetchJson` for the same read, returning the raw pg_graphql JSON without converting it to a GRT.
- `fetchJsonResult` for partial JSON together with upstream error messages, paths, locations, and
  extensions.
- `toGRT` (an extension on the `JsonObject` result) to convert JSON — from `fetchJson`, a cache, or
  any other source — into the generated Viaduct value for a typed selection set.
- `fetchNode` for results that also need requested node references.
- `fetchByInternalId`/`fetchByInternalIds` for the common filtered-collection node lookup.
- `fetchUuidIds` for collection resolvers that return Viaduct node references.
- `fetchUuidConnection` for caller-managed `first`/`after` or `last`/`before` pagination.
- `fetchNestedUuidConnections` for one paginated child connection per parent in one request.

`fetchResult` and `fetchJsonResult` return a `DbResult` containing data and any GraphQL errors from
pg_graphql. `fetch` and `fetchJson` use the same request code but throw
`UpstreamGraphqlException` if pg_graphql returns errors. Use `fetchJson` when the resolver needs to
inspect the JSON before converting it, or use `toGRT` to convert JSON obtained elsewhere:

```kotlin
val json = client.fetchJson(ctx, dbRead, selections)
val value = json.toGRT(ctx, selections)
```

The application supplies the HTTP client, endpoint, and request headers. The runtime converts the
`SelectionSet` into a pg_graphql query, sends the request, converts the returned JSON into GRTs,
and creates Viaduct node references from returned IDs. It uses generated connection GRT types to
recognize connection fields, so fields named `nodes` or `edges` on other GraphQL types are not
treated as connections.

## Writes

For an ordinary mutation resolver, select the persistent node type and pass its input directly.
The resolver's typed context determines the payload type, so the application does not name the
payload or copy IDs out of a pg_graphql response:

```kotlin
override suspend fun resolve(ctx: Context): AddGroupMemberPayload {
    val insert = ctx.arguments.input.toPgGraphqlInsert()
    return dbClient.entity<GroupMember>().insert(ctx, insert)
}
```

These operations provide insert, update, and delete for one selected node type. A call writes only
that node type's pg_graphql collection. Although the value encoder can represent nested inputs,
maps, and collections, it does not turn nested persistent nodes into additional inserts or updates.
Relationship writes use foreign-key ID fields, and a resolver must issue separate operations for
each related node type.

Batch insert writes several rows of the same selected type; it does not persist a mixed object
graph. Batch update and delete have the same one-type boundary. Updates preserve omitted fields and
send explicitly supplied nulls as null. Delete cascading is controlled only by application-owned
database constraints. These APIs do not provide upsert.

`insert`, `update`, and `delete` build the resolver's declared payload, create a node reference for
the returned record when the payload contains one matching node field, and initialize `userErrors`
to an empty list. A payload with no matching node field is valid for delete. A payload with an
ambiguous node field fails instead of choosing one.

Batch mutations use the same rules. `insertBatch` sends all inputs in one pg_graphql insert;
`updateBatch` and `deleteBatch` apply each independently identified input and combine the result
into the resolver payload:

```kotlin
dbClient.entity<GroupMember>().insertBatch(ctx, ctx.arguments.inputs.map { it.toPgGraphqlInsert() })
dbClient.entity<GroupMember>().updateBatch(ctx, ctx.arguments.inputs.map { it.toPgGraphqlUpdate<GroupMember>() })
dbClient.entity<GroupMember>().deleteBatch(ctx, ctx.arguments.inputs.map { it.toPgGraphqlDelete<GroupMember>() })
```

Update and delete require a generated Viaduct input containing an ID field whose `@idOf` target
matches the selected persistent node type. They cannot derive an ID passed as a separate mutation
argument. When exactly one input field matches, it identifies the row automatically. No match
fails, and multiple matches fail rather than choosing a field. Resolve an ambiguity explicitly:

```kotlin
val update = ctx.arguments.input.toPgGraphqlUpdate<Group>(identifierField = "groupId")
dbClient.entity<Group>().update(ctx, update)
```

The explicit field must exist and have the matching `@idOf` target. It becomes the `uuidId` filter
and is omitted from the update values. Batch update and delete use the same identifier field for
every input and issue one operation per input; batch insert uses one operation for all inputs.

Insert and update payloads require exactly one field matching the selected node type. A delete
payload may omit it. The entity API initializes `userErrors` but does not translate pg_graphql
errors into them; it throws on those errors.

Use `PgGraphqlMutationClient` when a resolver needs the records returned by pg_graphql instead of
having `DbClient.entity<T>()` build the resolver's mutation payload.

Use the `*Result` methods when the resolver needs a `DbResult` containing the returned data and
GraphQL errors. The `insert`, `update`, and `delete` methods instead throw
`UpstreamGraphqlException` when pg_graphql returns errors. The explicit `atMost` parameter prevents
an accidentally broad update or delete.

The `*Result` methods preserve both data and GraphQL errors. When the runtime changes the structure
of returned connection data, it makes the corresponding change to each error path. The resolver
must decide whether to return the data, represent the errors in its mutation payload, or throw an
exception. Methods without the `Result` suffix throw and therefore do not return data from a
response that also contains errors.

When a connection uses a join table, the runtime selects the
`<fieldName>Associations` pg_graphql field (for example, `membersAssociations`), even when the edge
contains only `node` and `cursor`. Pagination, filters, and ordering are applied to the join-table
rows. The runtime places `association.node` in the Viaduct edge's `node` field and places the other
columns in their corresponding edge fields. When the related object's table contains the foreign
key, the runtime selects that pg_graphql relationship directly. No edge view or SQL function is
required.

The pg_graphql endpoint should be accessible only to the Viaduct application. This runtime does not
make authorization decisions. The application applies checker executors before returning
persistent fields and does not expose its database credentials or pg_graphql endpoint to clients.
