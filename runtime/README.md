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
    endpoint = postgresGraphqlEndpoint,
    requestHeaders = DbRequestHeaders { context ->
        mapOf("Authorization" to "Bearer ${accessTokenFor(context)}")
    },
)
```

For JDBC, add `dev.viaduct.persistence:jdbc` at the same version as `runtime`, plus your
PostgreSQL driver, and supply a `DataSource`:

```kotlin
val executor = JdbcPgGraphqlExecutor(dataSource)
val client = DbClient(executor)
val mutations = PgGraphqlMutationClient(executor)
```

Each request obtains a connection, commits on success or rolls back on failure, and closes
the connection handle. With a pool, this returns the connection to the pool; it does not close
the pool. JDBC waits on the calling thread, so run datasource-based calls on threads intended
for blocking I/O, such as Kotlin's `Dispatchers.IO`:

```kotlin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val ids = withContext(Dispatchers.IO) {
    client.fetchUuidIds(ctx, "groupCollection")
}
```

To use an existing transaction, pass `JdbcPgGraphqlExecutor(connection)` instead, with autocommit
disabled. The caller owns that connection's commit, rollback, and close. JDBC does not automatically
apply HTTP headers or authorization; see [JDBC configuration](../docs/JDBC_TRANSPORT.md).

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
- `fetchByInternalIdsResult` for a batch-node-resolver map containing one Viaduct `FieldValue` per
  requested UUID.
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

The application supplies either an HTTP client, endpoint, and request headers, or a JDBC executor.
The runtime converts the `SelectionSet` into a pg_graphql query, sends the request, converts
the returned JSON into GRTs,
and creates Viaduct node references from returned IDs. It uses generated connection GRT types to
recognize connection fields, so fields named `nodes` or `edges` on other GraphQL types are not
treated as connections.

## Batch Node Results

Batch node resolvers can preserve the successful nodes when one requested row is missing or one
returned node has a pg_graphql error:

```kotlin
override suspend fun batchResolve(
    contexts: List<Context>,
): Map<Context, FieldValue<Group>> {
    val byId = dbClient.fetchByInternalIdsResult(
        ctx = contexts.first(),
        collectionField = "groupCollection",
        ids = contexts.map { it.id.internalID },
        ownedSelections = contexts.first().ownedSelections(),
        requestedSelections = contexts.first().selections(),
    )
    return contexts.associateWith { context -> byId.getValue(context.id.internalID) }
}
```

The result contains `FieldValue.ofValue(node)` for a successful UUID. A missing UUID contains
`FieldValue.ofError` with code `MISSING_ROW`; an upstream error whose path identifies a returned
edge becomes an error value for that edge's UUID. The resolver maps these values back to its
original contexts, allowing Viaduct to produce the final application response path and fail only
the affected node. An upstream error that cannot be associated with a returned edge is thrown
instead of being silently discarded.

Viaduct does not currently expose a supported GRT builder operation for assigning an error value
to one field of an otherwise successful GRT. Consequently, an upstream error on a field makes that
node's `FieldValue` erroneous; other nodes in the batch remain available, but successful sibling
fields on the affected node cannot yet be retained.

## Writes

For an ordinary mutation resolver, select the persistent node type, convert its input explicitly,
and pass the converted value.
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
graph. Batch update and delete also write only the selected node type. Updates preserve omitted fields and
send explicitly supplied nulls as null. Delete cascading is controlled only by application-owned
database constraints. These APIs do not provide upsert.

`insert`, `update`, and `delete` build the resolver's declared payload, create a node reference for
the returned record when the payload contains one matching node field, and initialize `userErrors`
to an empty list. A matching field uses the selected node type directly, or a union or interface
that includes it. A payload with no matching node field is valid for delete. If several fields
match, select one with `entityField`; otherwise the operation fails instead of choosing one.

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
and is omitted from the update values. Each batch input is converted separately and can select
its own identifier field. Batch update and delete issue one request per input; batch insert uses
one request for all inputs. With HTTP or a JDBC `DataSource`, earlier updates or deletes remain
committed if a later request fails. Use the transaction's batch methods when the changes must
commit together; a caller-owned JDBC connection instead leaves commit and rollback to its owner.

Insert and update payloads require one selected field that can represent the node type.
If the resolver returns a union or interface and several concrete payload types could hold the
result, select the concrete payload type with `payloadType`. A delete payload may omit the node
field. The entity API initializes `userErrors` but does not translate pg_graphql
errors into them; it throws on those errors.

Use `PgGraphqlMutationClient` when a resolver needs the records returned by pg_graphql instead of
having `DbClient.entity<T>()` build the resolver's mutation payload.

## Transactions

`DbClient.beginTransaction(ctx)` creates an in-memory mutation buffer. Calls on its selected entity
return operation handles without contacting pg_graphql. `commit()` sends the buffered insert,
update, and delete operations as aliased fields in one GraphQL mutation request. `abort()` clears
the buffer without sending a request. `commitResult()` follows the mutation error rules below.
`DbClient.transaction(ctx) { ... }` is the shorter form: its lambda exposes mutation operations,
not `commit()` or `abort()`. By default, it sends the buffered request after the block succeeds and discards
unsent operations if the block throws. The returned `DbTransactionCommit` contains both the
block's value and the database result, allowing the block to return operation handles for
looking up those results.

Each handle includes its transaction identity. Another transaction's results return null for that
handle. Saved results and locally prepared HTTP requests preserve the identity during restoration.

With HTTP or a `DataSource`, successful execution completes that request's database transaction.
With a caller-owned JDBC `Connection`, `commit()` and `transaction(ctx) { ... }` execute the
request but leave the database transaction open. Results are not committed until the caller
commits the connection. The caller must roll back on failure; `abort()` only discards unsent
operations and cannot roll back that connection.

Transaction operations accept `PgGraphqlObject`, `PgGraphqlUpdate`, and `PgGraphqlDelete`; Viaduct
inputs are converted explicitly before being added. All relationship IDs must be known before
commit, so related inserts use client-created UUIDs.

The live Supabase transaction test is enabled by `PG_GRAPHQL_API_KEY`. It uses
`http://127.0.0.1:54321/graphql/v1` and the `Group` type by default. Override those with
`PG_GRAPHQL_URL` and `PG_GRAPHQL_TRANSACTION_TYPE`. If the table uses a writable field other than
`name`, set `PG_GRAPHQL_TRANSACTION_LABEL_FIELD`. Supply any additional required insert fields as
JSON through `PG_GRAPHQL_TRANSACTION_OBJECT`.

The query `*Result` methods preserve successful fields and GraphQL errors with either transport.
For the lower-level mutation `*Result` methods and ordinary buffered `commitResult()`:

- HTTP returns the data and errors supplied by pg_graphql. Partial response data is not proof
  that writes committed.
- JDBC with a `DataSource` rolls back on GraphQL errors and returns errors with `data = null`.
- JDBC with a caller-owned `Connection` throws `UpstreamGraphqlException` on mutation errors,
  even from `Result` methods. The caller must roll back the enclosing transaction.

Methods without `Result` throw on GraphQL errors in all three cases. SQL, connection, and malformed
response failures may also throw from `Result` methods. When the runtime changes the structure
of returned connection data, it changes each error path to match. The lower-level mutation client's
`atMost` parameter limits how many matching rows an update or delete may change.

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
