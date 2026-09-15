# PG Persistence

PG Persistence lets a Viaduct application's GraphQL schema define its PostgreSQL data
model and provides a `DbClient` for resolving that data through `pg_graphql`.

For an explanation of the generated database model and runtime behavior, see
[ARCHITECTURE.md](ARCHITECTURE.md).

## Install

Make the plugin and runtime available to the application:

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
    id("dev.viaduct.pg-persistence") version "0.1.0-SNAPSHOT"
}

dependencies {
    implementation("dev.viaduct.persistence:runtime:0.1.0-SNAPSHOT")
}
```

The runtime is a Maven dependency of the application. The snapshot repository above provides the
current `0.1.0-SNAPSHOT`; released versions are available from Maven Central.

The plugin expects the application build to provide `assembleViaductCentralSchema`.

## Define Persistent Types

An object that implements Viaduct's `Node` interface is persistent by default. Object fields,
lists, and connections describe relationships:

```graphql
type Group implements Node {
  id: ID
  name: String
  members: [GroupMember]
}

type GroupMember implements Node {
  id: ID
  group: Group
  person: Person
}

type Person implements Node {
  id: ID
  displayName: String
}
```

An `ID` field with `@idOf` stores a reference without requiring an object field:

```graphql
type Person implements Node {
  id: ID
  groupId: ID @idOf(type: "Group")
}
```

The scalar ID field may also accompany a matching object field. These fields use the same foreign
key column:

```graphql
type Person implements Node {
  id: ID
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

List fields are supported only when their elements are persistent `Node` types. Nested lists and
lists of scalar, enum, or arbitrary non-persistent object values are not supported. Resolver-backed
fields that are not relationships between persistent types are not stored.

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

Unknown keys, types, field coordinates, and ineffective entries fail generation. Override only
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

## Resolve Persistent Nodes

```kotlin
return dbClient.fetchByInternalId(
    ctx = ctx,
    collectionField = "groupCollection",
    id = ctx.id.internalID,
    ownedSelections = ctx.ownedSelections(),
    requestedSelections = ctx.selections(),
)
```

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

The conversion functions convert typed global IDs. The client creates returned node references, fills the matching payload
field, and initializes `userErrors` to an empty list. Multiple matching payload fields are rejected
as ambiguous.

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

To create the Group, Person, and GroupMember together, the resolver must perform three inserts and
pass the created IDs into GroupMember. The current entity API sends those as separate requests.
Transactional multi-operation support requires one combined pg_graphql request with client-created
UUIDs and is tracked separately.

Batch operations do not change this rule. `insertBatch<Group>` inserts several Groups in one table;
it does not insert a mixed object graph. Batch update and delete likewise target one selected node
type.

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

The explicit field must exist in the input and have `@idOf(type: "Group")`. Batch update and delete
use the same identifier field for every input. They execute one pg_graphql operation per input;
batch insert sends all inputs in one operation.

Insert and update payloads must contain exactly one field matching the selected node type. Delete
payloads may omit that field. The entity API initializes `userErrors` to an empty list but does not
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
errors. Use `insertResult`, `updateResult`, or `deleteResult` to receive a `DbResult` containing
partial payload data and structured GraphQL errors. Headers are supplied per call; unlike
`DbClient`, this client does not derive them from an execution context.

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
metadata customization, schema-directory changes, or complete HBM replacement, see
[Custom configuration](docs/CUSTOM_CONFIGURATION.md).

## Requirements

- A Gradle build that provides `assembleViaductCentralSchema`
- PostgreSQL when applying generated SQL
- `pgcrypto` and `pg_graphql` for the complete PostgreSQL/GraphQL integration

Applications do not need to be implemented in Kotlin or run on the JVM to use the generated
PostgreSQL schema and pg_graphql API. The Gradle plugin and Viaduct runtime integration are JVM
tools, but that is not a requirement of the database interface.

## License

PG Persistence is licensed under the [Apache License, Version 2.0](LICENSE).
