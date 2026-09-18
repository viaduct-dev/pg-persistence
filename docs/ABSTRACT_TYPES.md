# Using Unions and Interfaces

## Stored relationships

Declare relationships using the application's union or interface. Every possible concrete type
must be an included persistent `Node`:

```graphql
union Subject = Person | Group

type Activity implements Node {
  id: ID
  title: String
  subject: Subject
  subjects: [Subject!]
}
```

Select fields using ordinary inline or named fragments:

```graphql
fragment Main on Activity {
  subject {
    ... on Person { displayName }
    ... on Group { name }
  }
}
```

The client returns the appropriate concrete GRT, including for mixed lists and connection nodes.
The generated runtime mapping must be on the application's classpath, along with its GRTs. The
plugin includes it in the generated resources.

## Reads whose result type is a union or interface

When the root selection itself is a union or interface, select a concrete table explicitly:

```kotlin
return dbClient.fetch(
    ctx,
    DbRead(
        DbRoot("personCollection", singleViaFilteredCollection = true),
        concreteType = Person.Reflection,
    ),
    ctx.selections(),
)
```

This reads a Person; it does not search or combine every possible type's table. Use a stored
connection with an abstract `edges.node` type when you need one paginated collection containing
different concrete types. Plain lists follow pg_graphql cursors internally and return the complete
collection; use a connection when callers need explicit page boundaries and cursors.

## Writing references

Given these input types:

```graphql
input ActivityValues { title: String }
input AddPersonActivityInput {
  activity: ActivityValues!
  personId: ID! @idOf(type: "Person")
}
```

Convert the values and then set the relationship explicitly:

```kotlin
val input = ctx.arguments.input
val values = input.activity.toPgGraphqlInsert()
    .withReference(Activity.Fields.subject, input.personId)
return dbClient.entity<Activity>().insert(ctx, values)
```

`withReference` sets the selected concrete foreign key and clears the others. Pass null to clear
a nullable reference. For an update, apply it to `PgGraphqlUpdate.values` with
`copy(values = ...)`. This does not insert or update the referenced node.

For a mixed collection, use the generated association table. Given an input with
`activityId: ID! @idOf(type: "Activity")` and `personId: ID! @idOf(type: "Person")`:

```kotlin
val association = Activity.Fields.subjects.pgGraphqlAssociation()
val row = association.insertObject(ctx.arguments.input.activityId, ctx.arguments.input.personId)
dbClient.transaction(ctx) {
    insert(association.entity, row)
}
```

The transaction scope also accepts `update(association.entity, PgGraphqlUpdate(...))` and
`delete(association.entity, PgGraphqlDelete(...))`. Use explicit filters to identify association
rows. `insertObject` accepts an optional converted value for stored edge fields, and
`withTarget` changes an association's target while clearing its other target IDs.

## Mutation payloads

Payload fields may be unions or interfaces that include the selected node type. The entity stays
concrete: `entity<Person>()` can populate a `subject: Subject` field. Batch methods populate a
list of that abstract type, but still write only Persons.

When the resolver's payload type is itself a union or interface, the client selects its sole
compatible concrete payload. If several payloads match, pass `payloadType`. If a payload contains
several compatible node fields, pass `entityField`:

```kotlin
return dbClient.entity<Person>().insert(
    ctx,
    ctx.arguments.input.toPgGraphqlInsert(),
    payloadType = SavePersonPayload.Reflection,
    entityField = "subject",
)
```

Both arguments are optional and must match the declared schema. Ambiguity and singular/list
mismatches fail before a database write. A delete returning an abstract payload without a node
field requires an explicit concrete `payloadType`.

## Limits

- Each mutation still targets one concrete table. Abstract types do not enable nested writes.
- Stored relationships are supported on persistent nodes, including lists and structural
  connections. Additional abstract relationships on custom connection edge fields are not supported.
- Abstract references need the concrete target's typed ID. A single `@idOf` field targeting a union
  or interface cannot identify which foreign-key column to use. Model generation rejects such
  fields on persistent objects and stored edge fields; declare the abstract relationship directly
  and use `withReference` with a concrete typed ID. Resolver-only fields are not stored.
- Generated association names and concrete foreign-key names must not collide with application
  schema names. Generation rejects collisions.
- Adding or removing possible types changes the database model. Review the migration and backfill
  existing data before tightening constraints or removing a target.
- Relationship filtering and ordering use the generated association table's pg_graphql input
  fields, not automatic cross-type filters on the application union or interface.

See [Architecture](../ARCHITECTURE.md) for database representation and response translation.

## Choosing possible types and changing them

Prefer a union or interface with the concrete types the relationship actually needs. A broad
interface such as `Node` can include many tables. Every possible type adds a foreign-key column
to the reference or association row and a concrete relationship selection to reads. There is no
automatic query across all of those tables, and a fragment selecting only one concrete type does
not remove the other stored targets.

Changing the possible types is a database migration, not just a GraphQL schema edit:

1. When adding a type, generate and review the new column, foreign key, and replacement
   target-count constraint. Apply the migration before code starts writing the new target.
2. Before removing a type, move existing references to an allowed target, clear nullable
   references, or remove the affected association rows. Required references cannot simply be
   cleared. Do not rely on the replacement constraint to detect every leftover reference:
   a nullable reference can appear empty when its old target column is no longer read.
3. Replace the constraint and remove obsolete columns only after the data is ready. Coordinate
   application versions and generated metadata so older instances do not continue using a
   removed target.

Existing data is not backfilled automatically. Test the migration against representative data,
including existing references and attempts to populate two target columns at once.

## Running the approval-request integration tests

`ApprovalRequestIntegrationTest` uses reduced Gateloom request shapes: an `ApprovalRequest` union
of `AccessRequest`, `ImportRequest`, and `OnboardingRequest`, with a test-only
`ReviewAssignment.request` relationship. The related suites add interfaces, inherited interfaces,
lists, connections, mutations, transactions, schema migrations, and Viaduct execution. They do
not change Gateloom's application schema.

Start local Supabase, then run:

```shell
./gradlew :plugin:test --tests '*Approval*IntegrationTest'
```

The fixture generates real GRT classes and database mappings from SDL, then uses `withReference`,
the mutation client, and `DbClient.fetch` to write and read the relationship. Only the HTTP hop
is replaced: every client document and its variables execute through PostgreSQL's
`graphql.resolve`. No database responses are mocked and no API key is required.

The default database is `jdbc:postgresql://127.0.0.1:54322/postgres`, with user/password `postgres`.
`PG_INTEGRATION_JDBC_URL`, `PG_INTEGRATION_USER`, and `PG_INTEGRATION_PASSWORD` can override these
defaults; the URL must remain local. An unavailable database fails the test rather than skipping it.
Each case creates uniquely named tables and removes them afterward, leaving application data alone.

The tests assert observable results: exact persisted IDs and values, concrete generated GRTs,
cursor navigation with custom edge fields, nullable references, reassignment and deletion,
batch payloads, ambiguity rejection before writing, and transaction commit, rollback, and abort.
Engine tests execute queries through Viaduct and check the final GraphQL data, aliases, checker
execution, non-null bubbling, and error paths; they do not stop at `DbResult` assertions.

Migration tests create disposable databases (the local user needs `CREATEDB`) and apply the
generated Liquibase diff and PostgreSQL/pg_graphql overlays. They verify that adding a possible
type preserves old references and supports new writes, reads, and foreign-key/check constraints.
Removal is tested with the destructive-review changes too: a required reference to the removed
type rejects the migration, and moving that reference allows the migration and subsequent reads.
The disposable databases are dropped afterward.
