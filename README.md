# PG Persistence

PG Persistence lets a Viaduct application's GraphQL schema define its PostgreSQL data
model and provides a `DbClient` for resolving that data through `pg_graphql`.

For an explanation of the generated database model and runtime behavior, see
[ARCHITECTURE.md](ARCHITECTURE.md), also available on
[Slate](https://slate.airbnb.tools/KhZmFfGHRL) (Airbnb access required).

## Install

Apply PG Persistence to each Viaduct module that owns database nodes. The module must already
apply the Viaduct module plugin and its Kotlin/KSP setup. For a single-project application:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        maven("https://central.sonatype.com/repository/maven-snapshots/")
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://central.sonatype.com/repository/maven-snapshots/")
        mavenCentral()
    }
}
```

```kotlin
// build.gradle.kts
plugins {
    id("com.airbnb.viaduct.application-gradle-plugin") version "<viaduct-version>"
    id("com.airbnb.viaduct.module-gradle-plugin") version "<viaduct-version>"
    id("dev.viaduct.pg-persistence") version "0.1.0-SNAPSHOT"
}

dependencies {
    implementation("dev.viaduct.persistence:runtime:0.1.0-SNAPSHOT")
}
```

The runtime is a Maven dependency of the application. The snapshot repository above provides the
current `0.1.0-SNAPSHOT`; released versions are available from Maven Central.

For a multi-project application, apply PG Persistence to the database-owning modules, not just the
application project. Each module supplies its schema to the application's normal
`assembleViaductCentralSchema` task.

## Define Persistent Types

An object that implements Viaduct's `Node` interface is persistent by default. Declare
`@resolver(isSelective: true)` on each persistent node and implement its node resolver in your
application. Viaduct's generated selective resolver contexts expose `ctx.selections()` and
`ctx.ownedSelections()` for `DbClient`. For batch node resolvers, also set `isBatching: true`.

The plugin's schema-validation task, which runs during compilation, requires an explicit selective
resolver declaration. A missing `@resolver`, an omitted `isSelective`, or `isSelective: false`
fails validation with instructions to add `@resolver(isSelective: true)`. A declaration on
`extend type` is also supported. Types excluded by `denyList.types` are not subject to this check.
PG Persistence does not add resolver declarations, rewrite schema files, or generate resolver
implementations. Viaduct assembles the application-authored schema normally.

Object fields, lists, and connections describe relationships:

```graphql
type Group implements Node @resolver(isSelective: true) {
  id: ID!
  name: String
  members: [GroupMember]
}

type GroupMember implements Node @resolver(isSelective: true) {
  id: ID!
  group: Group
  person: Person
}

type Person implements Node @resolver(isSelective: true) {
  id: ID!
  displayName: String
}
```

An `ID` field with `@idOf` stores a reference without requiring an object field:

```graphql
type Person implements Node @resolver(isSelective: true) {
  id: ID!
  groupId: ID @idOf(type: "Group")
}
```

The scalar ID field may also accompany a matching object field. These fields use the same foreign
key column:

```graphql
type Person implements Node @resolver(isSelective: true) {
  id: ID!
  group: Group
  groupId: ID @idOf(type: "Group")
}
```

The `@idOf` target must match the object field's type.

Supported stored fields are:

| GraphQL field | PostgreSQL representation |
| --- | --- |
| `ID` | UUID |
| `String` | Text |
| `Date` | Date |
| `DateTime` | Timestamp with time zone |
| `Time` | Time |
| `Boolean` | Boolean |
| `Byte`, `Short`, `Int`, `Long` | Matching integer type |
| `Float` | Double precision |
| `BigDecimal`, `BigInteger` | Numeric |
| `JSON` | JSONB |
| Enum | Text |
| Persistent object, list, or connection | Relationship |
| Union or interface of persistent nodes | Reference to one concrete node; lists and connections may mix types |

List fields are supported only when their elements are persistent `Node` types. Nested lists and
lists of scalar, enum, or arbitrary non-persistent object values are not supported. Resolver-backed
fields that are not relationships between persistent types are not stored.

List fields containing one concrete persistent node type follow pg_graphql cursors to load all
accessible references.
This can require multiple requests and is not a database snapshot across pages. Prefer a connection
for large collections: connections return only the requested page, with cursors for the next request.

Unions and interfaces are supported in reads, mutation payloads, and stored relationships.
Every concrete target of a stored relationship must be an included persistent `Node`.
See [Using unions and interfaces](docs/ABSTRACT_TYPES.md) for selection, mutation, and mixed
collection examples.

## SQL functions

Use the library's `PgGraphqlClient` for application-owned SQL functions exposed by pg_graphql.
For a function returning JSON or JSONB, `executeJson` decodes pg_graphql's string-encoded JSON
result with a Kotlin serializer. It also handles request-variable encoding:

```kotlin
import dev.viaduct.persistence.runtime.db.executeJson

val users = client.executeJson(
    document = "query { getAllUsersJson }",
    responseKey = "getAllUsersJson",
    deserializer = ListSerializer(UserRecord.serializer()),
    headers = authenticatedHeaders,
)
```

For other GraphQL return types, use `execute`. Its `PgGraphqlObject` overload accepts ordinary
field values as variables. SQL-function definitions and their authorization checks belong to the
application; no code for sending HTTP requests or reading the GraphQL response's `data` and
`errors` needs to be copied into it.

## Configure Persistence Policy

Optional persistence policy belongs in `src/main/viaduct/persistence.yaml`:

```yaml
denyList:
  types:
    - ExternalProfile

semanticNotNull:
  types:
    - Group
  fields:
    - Person.displayName

relationships:
  unidirectionalTargetForeignKeyFields:
    - Group.members
  inverseFieldOverrides:
    ExternalGroup.discordServerRoles: server
```

- `denyList.types` excludes an occasional `Node`. For a large externally backed schema, use a
  separate Viaduct tenant module without this plugin.
- `semanticNotNull` requires stored values while leaving public GraphQL field nullability intact.
- `relationships.unidirectionalTargetForeignKeyFields` names collection fields that store the
  relationship as a foreign key on the contained node's table. Each value uses `Type.field`, must
  identify a persistent collection, and cannot be used when the connection has stored edge fields.
- `relationships.inverseFieldOverrides` resolves a collection whose contained node type has more
  than one object field referring back to the collection's declaring type. The key is the
  collection's `Type.field`; the value is the exact object-field name on the contained type. The
  named field must exist and refer to the declaring type.

For example, if `DiscordServerRoleGroup` has both `externalGroup: ExternalGroup` and
`server: ExternalGroup`, the schema alone cannot determine which field stores
`ExternalGroup.discordServerRoles`. This entry selects `server`:

```yaml
relationships:
  inverseFieldOverrides:
    ExternalGroup.discordServerRoles: server
```

Unknown keys, types, `Type.field` names, and entries that have no effect fail generation. Override only
the file location from Gradle when needed:

```kotlin
viaductPgPersistence {
    persistenceConfigFile.set(layout.projectDirectory.file("config/persistence.yaml"))
}
```

## Generate and Apply Database Changes

```bash
./gradlew buildViaductEffectiveModel
```

Review the files under:

```text
build/generated/viaduct-effective-model/META-INF/
  postgresql-migration.sql
  postgresql-prerequisites.sql
  postgresql-repeatable.sql
  pg-graphql-metadata.sql
  pg-graphql.sql
```

Adapt `postgresql-migration.sql` into the application's migration system. Apply
`pg-graphql-metadata.sql` after the relational schema exists. `pg-graphql.sql` is a convenience
bundle for a fresh schema, not a repeatable production migration. The plugin never applies
database changes automatically.

### Compare with an Existing Database

```kotlin
viaductPgPersistence {
    schemaDiffUrl.set(providers.environmentVariable("SCHEMA_DIFF_DATABASE_URL"))
    schemaDiffUser.set(providers.environmentVariable("SCHEMA_DIFF_DATABASE_USER"))
    schemaDiffPassword.set(providers.environmentVariable("SCHEMA_DIFF_DATABASE_PASSWORD"))
}
```

```bash
./gradlew hibernateSchemaDiff
```

Review `build/schema-diff/hibernate-review.postgresql.sql` and
`build/schema-diff/hibernate-destructive-review.postgresql.sql`. Renames, removals, data
backfills, and database-owned constraints remain manual migration work.

## Configure `DbClient`

```kotlin
val dbClient = DbClient(
    httpClient = httpClient,
    endpoint = postgresGraphqlEndpoint,
    requestHeaders = DbRequestHeaders { context ->
        mapOf("Authorization" to "Bearer ${accessTokenFor(context)}")
    },
)
```

The endpoint and headers depend on the service exposing `pg_graphql`. Supabase normally uses
`https://<project>.supabase.co/graphql/v1` and expects both `Authorization` and `apikey` headers.

### Use JDBC instead of HTTP

Add the optional JDBC artifact and a PostgreSQL driver:

```kotlin
dependencies {
    implementation("dev.viaduct.persistence:jdbc:0.1.0-SNAPSHOT")
    runtimeOnly("org.postgresql:postgresql:42.7.5")
}
```

Supply your application's `DataSource`:

```kotlin
val executor = JdbcPgGraphqlExecutor(dataSource)
val dbClient = DbClient(executor)
val mutationClient = PgGraphqlMutationClient(executor)
```

The read and mutation APIs below stay the same. With a `DataSource`, each request executes
pg_graphql in a JDBC transaction and closes its connection afterward. For a connection pool,
closing the connection handle returns it to the pool; the application closes the pool at shutdown.
JDBC occupies the calling thread while waiting for the database. For example, use Kotlin's
`Dispatchers.IO`, which provides threads for blocking I/O:

```kotlin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val ids = withContext(Dispatchers.IO) {
    dbClient.fetchUuidIds(ctx, "groupCollection")
}
```

HTTP headers are not automatically applied to JDBC.
See [JDBC transport configuration](docs/JDBC_TRANSPORT.md) for caller-owned transactions,
request setup, and failure handling.

## Resolve Persistent Nodes

For the `Group` type above, add this mutation to `src/main/viaduct/schema/Group.graphqls`:

```graphql
input AddGroupInput {
  name: String!
}

type AddGroupPayload {
  group: Group
}

extend type Mutation {
  addGroup(input: AddGroupInput!): AddGroupPayload @resolver
}
```

Here is the complete `src/main/kotlin/com/example/groups/GroupResolvers.kt` file for a module
whose configured package is `com.example.groups`, using the default `viaduct.api.grts` package.
The application's resolver factory supplies the configured `DbClient` to both constructors.

```kotlin
package com.example.groups

import com.example.groups.resolverbases.MutationResolvers
import com.example.groups.resolverbases.NodeResolvers
import dev.viaduct.persistence.runtime.db.DbClient
import dev.viaduct.persistence.runtime.db.toPgGraphqlInsert
import viaduct.api.grts.AddGroupPayload
import viaduct.api.grts.Group
import viaduct.api.resolver.Resolver

@Resolver
class GroupNodeResolver(
    private val dbClient: DbClient,
) : NodeResolvers.Group() {
    override suspend fun resolve(ctx: Context): Group =
        dbClient.fetchByInternalId(
            ctx = ctx,
            collectionField = "groupCollection",
            id = ctx.id.internalID,
            ownedSelections = ctx.ownedSelections(),
            requestedSelections = ctx.selections(),
        )
}

@Resolver
class AddGroupResolver(
    private val dbClient: DbClient,
) : MutationResolvers.AddGroup() {
    override suspend fun resolve(ctx: Context): AddGroupPayload {
        val insert = ctx.arguments.input.toPgGraphqlInsert()
        return dbClient.entity<Group>().insert(ctx, insert)
    }
}
```

The mutation inserts the input and builds a payload containing a reference to the new group.
Selecting the group's fields invokes `GroupNodeResolver`, which fetches the requested data.
`NodeResolvers`, `MutationResolvers`, and the result types are generated from the schema.

`ownedSelections()` is the resolver's output selection set intersected with the current request's
selection set.

Other common operations are:

- `fetch` for an explicit `DbRead`.
- `fetchResult` for a `DbResult` containing a GRT and any GraphQL errors returned by pg_graphql.
- `fetchJsonResult` for the equivalent JSON result.
- `fetchNode` when returned node references must be attached.
- `fetchUuidIds` for a collection resolver that returns node references.
- `fetchUuidConnection` for `first`/`after` or `last`/`before` pagination.
- `fetchNestedUuidConnections` for the same child connection across several parents.

Pass cursor strings returned by pg_graphql back unchanged. Applications must not decode or
construct them.

### Return Batch Node Results

For a batch node resolver, `fetchByInternalIdsResult` returns one Viaduct `FieldValue` per
requested UUID. This example assumes the contexts have the same owned and requested selections
(including field arguments and variable values), and use the same database credentials and
authorization settings. It uses one context to execute the whole database request. If these
conditions differ, split the contexts into compatible batches or fetch each node separately;
do not use the first context's selections or credentials for unrelated contexts.

```kotlin
if (contexts.isEmpty()) return emptyMap()
val first = contexts.first()
val byId = dbClient.fetchByInternalIdsResult(
    ctx = first,
    collectionField = "groupCollection",
    ids = contexts.map { it.id.internalID },
    ownedSelections = first.ownedSelections(),
    requestedSelections = first.selections(),
)
return contexts.associateWith { context -> byId.getValue(context.id.internalID) }
```

Found nodes remain successful when another UUID is absent. A missing row becomes an error value
with code `MISSING_ROW`, and a pg_graphql error associated with one returned edge becomes an error
value for that node. Viaduct uses the original resolver context to place the error at the
application GraphQL response path. Errors that cannot be associated with an edge are thrown rather
than discarded. Until Viaduct provides a supported way to put an error value on an individual GRT
field, a field error fails its node while preserving the other nodes in the batch.

## Resolve Mutations

Convert the Viaduct input into a pg_graphql value, then pass that value to the selected persistent
node type. The resolver context supplies the payload type:

```kotlin
override suspend fun resolve(ctx: Context): AddGroupMemberPayload {
    val insert = ctx.arguments.input.toPgGraphqlInsert()
    return dbClient.entity<GroupMember>().insert(ctx, insert)
}
```

The same API supports updates, deletes, and batches:

```kotlin
dbClient.entity<Group>().update(ctx, ctx.arguments.input.toPgGraphqlUpdate<Group>())
dbClient.entity<GroupMember>().delete(ctx, ctx.arguments.input.toPgGraphqlDelete<GroupMember>())

dbClient.entity<GroupMember>().insertBatch(ctx, ctx.arguments.inputs.map { it.toPgGraphqlInsert() })
dbClient.entity<GroupMember>().updateBatch(ctx, ctx.arguments.inputs.map { it.toPgGraphqlUpdate<GroupMember>() })
dbClient.entity<GroupMember>().deleteBatch(ctx, ctx.arguments.inputs.map { it.toPgGraphqlDelete<GroupMember>() })
```

These are alternative calls for different mutation resolvers. `updateBatch` and `deleteBatch`
execute one request per input. With HTTP or a JDBC `DataSource`, earlier writes remain committed
if a later request fails. For all-or-nothing changes, use the batch methods on the transaction's
`entity<T>()` inside `dbClient.transaction(ctx) { ... }`. A caller-owned JDBC connection instead
leaves commit and rollback to its owner. `insertBatch` uses one list insert request.

The conversion functions convert typed global IDs. The client creates returned node references,
fills the payload field whose type can represent the selected node type, and initializes
`userErrors` to an empty list. That field can use the node's type directly or a union or interface
that includes it. If several fields could hold the result, select one with `entityField`.
If the resolver returns a union or interface and several concrete payload types could hold the
result, select the concrete payload type with `payloadType`. These are optional
arguments, validated before writing. See [abstract mutation payloads](docs/ABSTRACT_TYPES.md#mutation-payloads).

### Mutation limitations

The entity API provides pg_graphql insert, update, and delete operations for one persistent node
type at a time. Each call writes only the table represented by `entity<T>()`. It does not inspect an
input object for other persistent node types and does not turn nested objects into additional
database operations.

For example, an `insert<Group>` can write the Group's scalar fields and foreign-key ID fields. An
input shaped like this does not also insert the people:

```graphql
input AddGroupInput {
  name: String!
  members: [AddPersonInput!]
}
```

pg_graphql's `GroupInsertInput` has no nested insert operation for `members`, so passing that field
causes a pg_graphql input error. To refer to an existing node, pass the corresponding typed ID field:

```graphql
input AddGroupMemberInput {
  groupId: ID! @idOf(type: "Group")
  personId: ID! @idOf(type: "Person")
}
```

To create the Group, Person, and GroupMember together, the resolver performs three inserts and
passes client-created UUIDs into GroupMember. Use a transaction to commit those inserts together.

### Use transactions

Use `dbClient.transaction(ctx) { ... }` to group mutations in one database transaction.
The same API works with or without DBOS; choose the implementation when configuring `DbClient`.
Without DBOS, use the HTTP or JDBC client configured above. No additional dependency is needed.

For example, add this mutation and its input types alongside the Group, GroupMember, and Person
types defined above:

```graphql
input NewGroupInput {
  uuidId: String!
  name: String!
}

input NewGroupMemberInput {
  uuidId: String!
  groupId: ID! @idOf(type: "Group")
  personId: ID! @idOf(type: "Person")
}

input CreateGroupWithMemberInput {
  group: NewGroupInput!
  membership: NewGroupMemberInput!
}

extend type Mutation {
  createGroupWithMember(input: CreateGroupWithMemberInput!): Boolean!
}
```

Supply client-created UUID strings for both `uuidId` fields. `membership.groupId` is a Group
GlobalID whose `internalID` equals `group.uuidId`; it refers to the group this transaction will
create. `membership.personId` identifies an existing Person. In Kotlin, the group reference can
be constructed with `ctx.globalIDFor(Group.Reflection, input.group.uuidId)`. The explicit input
conversion sends its internal UUID to pg_graphql, not the encoded GlobalID.

The resolver converts the two input GRTs separately, then inserts both in one transaction:

```kotlin
override suspend fun resolve(ctx: Context): Boolean {
    val input = ctx.arguments.input
    val group = input.group.toPgGraphqlInsert()
    val membership = input.membership.toPgGraphqlInsert()

    dbClient.transaction(ctx) {
        entity<Group>().insert(group)
        entity<GroupMember>().insert(membership)
    }

    return true
}
```

`toPgGraphqlInsert()` converts generated Viaduct input GRTs, not output objects constructed with
`Group.Builder`. Each converted input must contain fields accepted by its table's pg_graphql insert
input, including `membership.personId` for the existing person. Typed ID fields use `@idOf`.

Only operations called through the transaction participate in it: use `entity<T>()` inside the
lambda, or `transaction.entity<T>()` with explicit transaction control. Ordinary calls such as
`dbClient.entity<T>().insert(ctx, value)` or `dbClient.fetch(...)` do not automatically join,
even when written inside the lambda. This applies with and without DBOS; do not mix those calls
when the work must succeed or fail together.

To use DBOS, add `dev.viaduct.persistence:dbos` at the same version as `runtime`, plus a PostgreSQL
JDBC driver, and configure the client once. DBOS brings Kotlin standard library 2.4.0; Kotlin
applications must use a compatible compiler (the integration is tested with Kotlin 2.4.0).
Applications using only the runtime or plain JDBC modules do not inherit this requirement.

```kotlin
val dbClient = DbClient(
    executor = JdbcPgGraphqlExecutor(dataSource),
    transactions = DbosTransactions(dbos, dataSource),
)
```

Run the transaction block from a registered DBOS workflow. Configuring the client does not make an
ordinary resolver invocation a workflow. See [DBOS transactions](docs/DBOS_TRANSACTIONS.md) for
workflow registration, calling application code from a workflow, and recovery requirements.

With either implementation, successful completion commits the mutations together, and a failed
block does not commit them. The exception is plain JDBC with a caller-owned `Connection`: the caller
must still commit or roll back that connection. Keep external service calls outside the block;
they cannot be rolled back, and DBOS may rerun the block after a retryable database failure.

Supply all mutation inputs up front, including IDs needed by related inserts. The shared API
does not expose earlier operation results inside the block. To retrieve results afterward, return
an operation handle from the lambda:

```kotlin
val committed = dbClient.transaction(ctx) {
    entity<Group>().insert(ctx.arguments.input.group.toPgGraphqlInsert())
    entity<GroupMember>().insert(ctx.arguments.input.membership.toPgGraphqlInsert())
}
val membership = committed.result[committed.value]
```

The last expression returns the membership insert's handle. `membership` is its pg_graphql mutation
payload, containing `affectedCount` and `records { uuidId }`, not a GroupMember GRT. Return a list
of handles to retrieve several operation results. Handles only work with their own transaction's
results.

#### Begin, commit, or abort explicitly

Without DBOS, use `beginTransaction(ctx)` when application code needs explicit transaction control.
The same group and membership inputs can be inserted as follows:

```kotlin
val transaction = dbClient.beginTransaction(ctx)
val membershipHandle = try {
    transaction.entity<Group>().insert(ctx.arguments.input.group.toPgGraphqlInsert())
    transaction.entity<GroupMember>().insert(ctx.arguments.input.membership.toPgGraphqlInsert())
} catch (failure: Exception) {
    transaction.abort()
    throw failure
}

val result = transaction.commit()
val membership = result[membershipHandle]
```

`commit()` completes the transaction and returns its operation results, or throws on failure.
To cancel before committing, call `abort()` instead:

```kotlin
val transaction = dbClient.beginTransaction(ctx)
transaction.entity<Group>().insert(ctx.arguments.input.group.toPgGraphqlInsert())
transaction.abort()
```

Nothing is written in this example. `abort()` discards unsent operations; it cannot undo a request
already sent or a completed commit. A completed or aborted transaction cannot be reused, and an
empty transaction cannot be committed. With a caller-owned JDBC `Connection`, `commit()` sends
the operations but the caller must still call `connection.commit()` or `connection.rollback()`;
`abort()` does neither. DBOS does not support these explicit-control methods; use the lambda form.

#### Inspect commit errors

Use `commitResult()` instead of `commit()` to receive a `DbResult<DbTransactionResult>`:

```kotlin
val transaction = dbClient.beginTransaction(ctx)
val handle = transaction.entity<Group>().insert(ctx.arguments.input.group.toPgGraphqlInsert())
val outcome = transaction.commitResult()
val errors = outcome.errors
val group = outcome.data?.get(handle)
```

Inspect `errors` before treating `group` as a successful write. HTTP may return partial data with
errors; that data does not prove a commit. A JDBC `DataSource` discards rolled-back mutation data.
With a caller-owned JDBC connection, mutation errors throw even from `commitResult()`, and the
caller must roll back. Transport and decoding failures can also throw. Call either `commit()` or
`commitResult()`, not both. See
[mutation results and connection ownership](#mutation-results-and-connection-ownership) for error
handling, and [transaction implementation details](ARCHITECTURE.md#mutation-execution) for how the
default implementation works.

#### Transaction mutation operations

Both the lambda scope and an explicit transaction expose these operations through `entity<T>()`:

| Operation | Input | Result handle |
| --- | --- | --- |
| `insert(value)` | `input.toPgGraphqlInsert()` | One handle |
| `insertBatch(values)` | Several converted insert inputs | One handle for the batch |
| `update(value)` | `input.toPgGraphqlUpdate<T>()` | One handle |
| `updateBatch(values)` | Several converted update inputs | One handle per input |
| `delete(value)` | `input.toPgGraphqlDelete<T>()` | One handle |
| `deleteBatch(values)` | Several converted delete inputs | One handle per input |

Each operation targets one selected node type; batches do not write a mixed object graph
automatically. For a generated association table without an application GRT, both forms also
accept `insert(entity, value)`, `update(entity, value)`, and `delete(entity, value)` directly,
using `PgGraphqlEntity` and the corresponding pg_graphql input value.

### Mutation inputs and payloads

An update sends only fields present in the Viaduct input after removing its selected identifier.
An omitted field is not changed; an explicitly supplied null is sent as null. Delete removes only
the selected rows. Any cascading delete behavior comes from application-owned database constraints,
not recursive behavior in PG Persistence. The entity API does not provide upsert.

Insert conversion accepts a generated Viaduct input whose supplied fields are valid for pg_graphql's
insert input for the selected node. Update and delete conversion require that input to contain an ID field whose
`@idOf` target is the type selected by `entity<T>()`. The ID must be inside the input;
the entity API does not inspect a separate mutation argument. The client converts that GlobalID to
the row's `uuidId` filter and excludes the selected field from the values sent by update.

The client automatically uses the matching `@idOf` field when exactly one exists. No match fails
because the operation cannot identify a row. If more than one field matches, the operation fails
rather than guessing; select the identifying field explicitly:

```kotlin
val update = ctx.arguments.input.toPgGraphqlUpdate<Group>(identifierField = "groupId")
dbClient.entity<Group>().update(ctx, update)
```

The explicit field must exist in the input and have `@idOf(type: "Group")`. Convert each input
separately before a batch update or delete. Each conversion can select a different identifier
field; the batch accepts already-converted operations, not a shared identifier-field argument.
Every operation must still target the selected node type. Batch update/delete execute one
pg_graphql operation per input; batch insert sends all inputs in one operation.

Insert and update payloads must identify one field whose type can represent the selected node,
either automatically when only one field matches or explicitly with `entityField`. Delete
payloads may omit that field. When present, delete payloads contain references with the deleted
IDs, not snapshots of the deleted rows; other fields cannot be fetched from those rows afterward.
The entity API initializes `userErrors` to an empty list but does not
convert pg_graphql errors into application `userErrors`; operations throw on those errors. Use the
lower-level `PgGraphqlMutationClient` for explicit filters, returned database records, partial data,
or structured pg_graphql errors.

Use `PgGraphqlMutationClient` directly when a resolver needs the records returned by pg_graphql
instead of having `DbClient.entity<T>()` build the resolver's mutation payload.

### Use `PgGraphqlMutationClient` directly

The lower-level client executes pg_graphql collection mutations without building a Viaduct payload:

```kotlin
val mutations = PgGraphqlMutationClient(httpClient, postgresGraphqlEndpoint)
val group = PgGraphqlEntity("Group")

val inserted = mutations.insert(
    entity = group,
    objectValue = ctx.arguments.input.toPgGraphqlInsert(),
    selection = "affectedCount records { uuidId name }",
    headers = mapOf("Authorization" to "Bearer $accessToken"),
)
```

This lower-level client accepts pg_graphql JSON. Convert a Viaduct input separately or construct
the pg_graphql values directly. Typed GlobalIDs are converted by `toPgGraphqlInsert`.

Update and delete accept explicit pg_graphql filters. `atMost` is required, must be greater than
zero, and limits how many matching rows pg_graphql may change:

```kotlin
val filter = buildJsonObject {
    put("uuidId", buildJsonObject { put("eq", groupId.internalID) })
}

val updated = mutations.update(
    entity = group,
    set = buildJsonObject { put("name", "New name") },
    filter = filter,
    atMost = 1,
    selection = "affectedCount records { uuidId name }",
    headers = requestHeaders,
)

val deleted = mutations.delete(
    entity = group,
    filter = filter,
    atMost = 1,
    headers = requestHeaders,
)
```

The `selection` is the selection inside the pg_graphql mutation payload; its default is
`affectedCount`. Methods without `Result` throw `UpstreamGraphqlException` when pg_graphql returns
errors. Use `insertResult`, `updateResult`, or `deleteResult` to receive a `DbResult`, subject
to the connection-ownership rules below. Headers are supplied per call; unlike
`DbClient`, this client does not derive them from an execution context.

### Mutation results and connection ownership

For `insertResult`, `updateResult`, `deleteResult`, and an ordinary buffered transaction's
`commitResult()`, a GraphQL error is handled as follows:

| Connection mechanism | Behavior when pg_graphql returns mutation errors |
| --- | --- |
| HTTP | Returns the data and errors supplied by pg_graphql. Partial response data is not proof that writes committed. |
| JDBC with a `DataSource` | Rolls back the request and returns errors with `data = null`; rolled-back mutation data is discarded. |
| JDBC with a caller-owned `Connection` | Throws `UpstreamGraphqlException`, even from a `Result` method. The caller must roll back the enclosing transaction. |

Methods without the `Result` suffix throw on GraphQL errors in all three cases. Query methods
such as `fetchResult` and `fetchJsonResult` preserve successful query fields alongside their
errors with either transport. SQL, connection, and malformed-response failures may still throw
from `Result` methods.

### Retry a transaction safely

This recovery feature uses HTTP; selecting JDBC alone does not provide durable retry or recovery.
After [enabling retryable transactions](docs/CUSTOM_CONFIGURATION.md#retryable-transactions),
give the transaction a stable operation ID. Using the generated inputs from the transaction
example above:

```kotlin
val input = ctx.arguments.input
val group = input.group.toPgGraphqlInsert()
val membership = input.membership.toPgGraphqlInsert()

dbClient.transaction(ctx, operationId = requestId) {
    entity<Group>().insert(group)
    entity<GroupMember>().insert(membership)
}
```

`requestId` is an application-supplied identifier for this logical operation, reused on retries.
The library resends the same GraphQL document and variables without rerunning the lambda or
resolver. A repeated operation returns its saved database result. Reusing the ID with different
commands fails. Keep UUIDs and
other input values unchanged.

To save a request before sending it, use `beginTransaction(ctx, operationId)`, add operations,
then call `prepare().encode()`:

```kotlin
val transaction = dbClient.beginTransaction(ctx, operationId = requestId)
transaction.entity<Group>().insert(ctx.arguments.input.group.toPgGraphqlInsert())
transaction.entity<GroupMember>().insert(ctx.arguments.input.membership.toPgGraphqlInsert())
val saved = transaction.prepare().encode()
// Persist saved in application-controlled storage before committing.
val result = transaction.commit()
```

`prepare()` freezes the operations; do not add more afterward. The application must store `saved`
somewhere that survives process restarts. To resume that saved request after a failure or restart:

```kotlin
val result = dbClient.resumeTransaction(ctx, DbPreparedTransaction.decode(saved))
```

To look up an already committed operation without submitting it:

```kotlin
val recovered = dbClient.lookupTransaction(ctx, operationId = requestId)
```

Recovery uses fresh request headers and checks that the authenticated transaction scope still
matches. The scope is an
application-supplied identifier derived from authentication, such as a tenant and caller ID.
It separates operation IDs belonging to different tenants or callers, so one cannot recover
another's result. See the [identity callback configuration](docs/CUSTOM_CONFIGURATION.md#retryable-transactions).

`lookupTransaction(ctx, operationId)` returns the saved request and result, or null if no committed
record is visible. Null does **not** prove rollback: an earlier request may still be running.
`DbTransactionException.outcome` distinguishes an unknown outcome from a confirmed commit whose
result could not be decoded. Preserve its `prepared` request when recovering; do not generate a new ID.
Cancellation also does not prove rollback. Prepare and persist first if recovery after cancellation
or process exit is required.

See [transaction implementation details](ARCHITECTURE.md#retryable-transactions)
for concurrency, permissions, and retention limitations.

## Gradle Tasks

| Task | Use |
| --- | --- |
| `validateViaductPgPersistenceSchema` | Validate schema and policy compatibility |
| `generateViaductPgPersistenceModel` | Generate persistence metadata |
| `buildViaductEffectiveModel` | Generate PostgreSQL and pg_graphql SQL |
| `hibernateSchemaSnapshot` | Write a database-model snapshot |
| `hibernateSchemaDiff` | Compare the generated model with PostgreSQL |

## Custom Configuration

Most applications should use the generated defaults. For custom naming strategies, Hibernate
metadata customization, schema-directory changes, or replacing the generated Hibernate XML
mappings entirely, see [Custom configuration](docs/CUSTOM_CONFIGURATION.md).

## Requirements

- A Gradle build that provides `assembleViaductCentralSchema`
- PostgreSQL when applying generated SQL
- `pgcrypto` and `pg_graphql` for the complete PostgreSQL/GraphQL integration

Applications do not need to be implemented in Kotlin or run on the JVM to use the generated
PostgreSQL schema and pg_graphql API. The Gradle plugin and Viaduct runtime integration are JVM
tools, but that is not a requirement of the database interface.

## License

PG Persistence is licensed under the [Apache License, Version 2.0](LICENSE).
