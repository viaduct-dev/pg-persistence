# PG Persistence Architecture

This document explains how PG Persistence turns a Viaduct GraphQL schema into a PostgreSQL
model and connects Viaduct resolvers to `pg_graphql`. For installation and application examples,
see [README.md](README.md).

## Components

| Module | Responsibility |
| --- | --- |
| `plugin` | Reads the assembled schema, builds the persistence model, and generates database files |
| `runtime` | Executes database GraphQL requests and converts results into Viaduct result types |

The application owns its schema, migrations, HTTP client, endpoint, credentials, and checker
executors. Model generation does not connect to or modify a database.

## Model Generation

The Gradle tasks:

1. Read persistent types and fields from the assembled GraphQL schema and `persistence.yaml`.
2. Generate Hibernate mapping metadata using GraphQL type and field names.
3. Ask Hibernate how that mapping corresponds to tables, columns, and relationships.
4. Generate PostgreSQL and pg_graphql SQL files.
5. When requested, compare the generated database description with an existing database.

No Java or Kotlin entity classes are generated. The mapping uses Hibernate's dynamic-map support.
The intermediate HBM and persistence-unit files are written under
`build/generated/viaduct-persistence/`; reviewed database files are written under
`build/generated/viaduct-effective-model/META-INF/`.

## Persistent Types and Relationships

The plugin starts with Viaduct `Node` types and excludes types named by the persistence policy. A
field whose GraphQL type is one of the remaining `Node` types is represented by a foreign key. A
list or connection whose elements are one of those `Node` types is represented either by a foreign
key on that type's table or by an association table.

Connection and edge types describe the GraphQL API and do not get their own entity tables. For a
connection, the plugin uses the type of `edges.node` to determine which `Node` type the connection
contains. Other scalar and object fields on the edge are represented by columns on the association
table row.

Relationship storage follows these rules:

- A field such as `GroupMember.group: Group` becomes a foreign key on the `GroupMember` table.
- A matching pair such as `GroupMember.group: Group` and
  `GroupMember.groupId: ID @idOf(type: "Group")` uses one `groupId` foreign-key column. pg_graphql
  exposes the column as `groupId` and the related object as `group`.
- When `Group.members: [GroupMember]` is paired with `GroupMember.group: Group`, both fields use the
  foreign key on the `GroupMember` table.
- When only `Group.members: [GroupMember]` exists, the foreign key is also placed on the
  `GroupMember` table.
- When both sides are collections, such as `Group.members: [Person]` and `Person.groups: [Group]`,
  both fields use one association table.
- Two fields on the same type that each contain the same `Node` type use separate association
  tables.
- A field that refers to the same `Node` type on which it is declared uses separate columns for
  the two ends of the relationship.

Association tables use the same default database schema as persistent node tables. Their
pg_graphql relationship is named `<fieldName>Associations`, such as `membersAssociations`.

## Unions and Interfaces

Unions and interfaces belong to the application's Viaduct schema. A union names its possible
object types; an interface declares fields shared by its implementing object types. Interfaces
may also implement other interfaces. The library resolves both forms to their possible concrete
types, including implementations of inherited interfaces.

pg_graphql does not combine separate tables into these application types. Each concrete
persistent `Node` keeps its own table. The plugin generates the foreign keys and metadata needed
to represent relationships, and the runtime converts between those database relationships and
the application's union or interface fields. A union or interface with only one possible type
still follows this process.

### Generated model and metadata

Concrete, union, and interface relationships share the model-building process. Each stored field
is described by its declared type, concrete targets, nullability, and whether it is a single
reference, list, or connection. Validation, field generation, and Hibernate mapping use those
descriptions rather than maintaining a separate model for each kind of relationship.

The plugin writes `META-INF/viaduct-persistence-abstract-types.json` alongside the Hibernate
mapping. It records possible concrete types and stored relationships. The runtime loads and
validates it once, then looks up relationships by owner type and field. Conflicting mappings,
duplicate relationships, and invalid target or connection definitions fail validation. Metadata
and generated field, builder, and concrete type classes use the owning GRT classloader.

Entity fields and stored edge fields share field processing and nullability rules. Concrete and
abstract association rows share construction and Hibernate mapping, while retaining their
respective generated names. Constraints use Hibernate's physical column names; pg_graphql
comments preserve the input and relationship names expected by the runtime.

### Database representation

For `union Subject = Person | Group`, a field `Activity.subject: Subject` has nullable
`subjectPersonId` and `subjectGroupId` foreign keys on the activity table. Each foreign key
requires its referenced row to exist. A PostgreSQL CHECK constraint allows at most one populated
target, or requires exactly one when the field is non-null. `semanticNotNull` also makes the
reference required. An interface with the same two possible concrete types uses the same storage.

A mixed list or connection uses one generated `<Owner><Field>Reference` association entity.
`Activity.subjects`, for example, uses `ActivitySubjectsReference`: each row has an owner foreign
key and `nodePersonId`/`nodeGroupId` target foreign keys. Every row must have exactly one target.
Stored edge fields share that row; connection and edge types do not become separate entity tables.

Unlike ordinary concrete association connections, the mixed collection's pg_graphql relationship
uses the public field name, without an `Associations` suffix. One pg_graphql connection provides
ordering, cursors, and pagination over the association rows, regardless of which concrete type
each row references. Abstract plain lists do not automatically fetch beyond pg_graphql's default page.
Concrete node lists follow provider cursors until complete; explicit connections retain their
requested page boundaries. Additional pages use the same request credentials but separate queries,
so concurrent writes may be visible between pages.

### From a selection set to a concrete GRT

For a selection such as `subject { ... on Person { displayName } ... on Group { name } }`:

1. The runtime uses the generated mapping to find the concrete relationships for `subject`.
2. It expands named fragments and selects the fields that apply to each concrete type, preserving
   directives and application aliases. The pg_graphql request selects through the corresponding
   foreign-key relationships, not an application-level `Subject` union.
3. After pg_graphql responds, the runtime identifies the populated target and restores one
   `subject` object with its concrete `__typename`. Multiple populated targets or conflicting
   concrete types are errors.
4. It restores collection nodes and edge fields to their application shape and translates
   upstream error paths back to application response aliases and list indexes.
5. Viaduct's JSON/GRT conversion builds the concrete generated object. JSON response aliases
   become schema field names in the resolver-returned GRT; Viaduct applies the requested aliases
   when completing the final GraphQL response.

`AbstractSelectionTranslator` coordinates `ConcreteSelectionProjector`,
`AbstractRelationshipSelection`, and `AbstractConnectionSelection`. Concrete and abstract
connections share `AssociationEdgeSelections`: cursors remain on edges, while stored fields are
selected from association rows. Reserved internal aliases keep these rows distinct from public
selections and are removed during response and error-path restoration.

`AbstractResponseRestorer` delegates connection nodes, lists, type names, and references to
separate field transformers. Ordinary reads and reads that populate node references share this
restoration and semantic non-null validation. A node reference uses the restored concrete
`__typename` and `uuidId`; unknown types are not guessed. Viaduct still executes the application's
checker executors when resolving selected fields.

When the read root itself is a union or interface, `DbRead.concreteType` must identify the one
table to query. The runtime narrows the selection with Viaduct's `selectionSetFor(concreteType)`
before GRT conversion. This is not a query across every possible type's table.

### References, mutation payloads, and transactions

`withReference` uses the generated mapping to set the chosen concrete target ID and clear all
other target IDs. Passing null clears a nullable reference. It changes the relationship only;
it does not create or update the referenced node. `PgGraphqlAssociation.insertObject` and
`withTarget` do the equivalent work for mixed collection rows. Association writes are explicit
insert, update, and delete operations; no application GRT is generated for the association table.

`DbClient.entity<Person>()` still writes Persons even if its payload field is a union or interface.
The runtime validates the concrete payload, matching entity field, and singular/list cardinality
before writing. It then uses returned record IDs to construct concrete node references. An
abstract payload with several compatible concrete types requires `payloadType`; several matching
fields require `entityField`. Batch payloads contain lists of references, but each batch still
targets one concrete entity type. Delete references contain deleted IDs, not deleted-row snapshots.

Entity and association operations can share a `DbTransaction`. The resolver supplies their IDs
before commit, typically using client-created UUIDs. The transaction sends one pg_graphql mutation
request, so a later failed association write rolls back earlier inserts, updates, and deletes in
that request. Unions and interfaces do not add nested writes or change the transaction lifecycle
described in [Mutation Execution](#mutation-execution).

### Limitations and schema changes

Every possible type of a stored relationship must be an included persistent `Node`. Persisted
`@idOf` fields cannot target a union or interface: reference helpers need a concrete typed ID to
choose a foreign key. Additional abstract relationships on custom edge fields are unsupported.
Generated names must not collide with application names.

A broad interface such as `Node` adds a foreign-key column for every possible target and additional
relationship selections on reads. Filtering and ordering use the generated pg_graphql fields;
the library does not invent cross-type filters.

Adding or removing possible types changes columns, foreign keys, constraints, and runtime metadata.
Migrations require review and coordinated application updates. Existing references are not
automatically moved: before removing a type, move its references to allowed targets, clear nullable
references, or delete affected association rows. A replacement constraint can reject a required
reference but may not detect an obsolete nullable reference, so it does not replace data migration.

See [Using unions and interfaces](docs/ABSTRACT_TYPES.md) for resolver examples and
[integration coverage](docs/ABSTRACT_TYPES.md#running-the-approval-request-integration-tests) for
database-backed tests of generated GRTs, mutations, transactions, migrations, and final Viaduct
GraphQL results.

## Persistence Policy

The YAML file changes persistence behavior without rewriting the GraphQL schema.

`denyList.types` excludes the named `Node` types. Generation fails if a type that remains included
has a field referring to an excluded type.

`semanticNotNull` makes the database representation stricter than the public GraphQL field:

```text
non-null in persistence = GraphQL SDL non-null
                       OR containing type is in semanticNotNull.types
                       OR field is in semanticNotNull.fields
```

Type-level policy applies to non-list fields declared on that object and does not apply to fields
on other objects reached through a relationship.

For each type named by `semanticNotNull.types`, generation records the applicable `Type.field`
names together with the names in `semanticNotNull.fields`. Methods that return a `DbResult` accept
a null when pg_graphql also returns an error for that response path. Otherwise, they add a
`SEMANTIC_NON_NULL_VIOLATION` error.

## Generated Database Files

The PostgreSQL files create or describe tables, columns, constraints, internal IDs, and
association rows. The pg_graphql metadata preserves the GraphQL type and relationship names used
by the runtime.

The combined `pg-graphql.sql` file bootstraps a new schema. Existing applications review relational
changes as migrations and apply `pg-graphql-metadata.sql` as repeatable metadata.

The pg_graphql schema is used only between the resolver and PostgreSQL. It may contain columns and
inverse relationships needed for filtering that are not fields in the application's GraphQL
schema.

## Read Execution

### HTTP and JDBC transport

`DbClient` and `PgGraphqlMutationClient` use `PgGraphqlExecutor` to execute the generated document
and variables. Their HTTP constructors use `HttpPgGraphqlExecutor`; the optional `jdbc` artifact
provides `JdbcPgGraphqlExecutor`. Both use the same envelope decoder and retain the same selection
translation, GRT conversion, and result mapping. HTTP preserves caller-supplied headers.

JDBC binds the document, variables, and optional operation name to
`SELECT graphql.resolve(?, ?::jsonb, ?)`. With a `DataSource`, it owns one transaction per
request and rolls back on GraphQL errors, malformed responses, or execution failures. Successful
query fields remain available with their errors; rolled-back mutation data is discarded.

With an existing `Connection`, it requires autocommit to be disabled and never commits, rolls
back, or closes that connection. Mutation errors throw so the caller can roll back the enclosing
transaction. A buffer's `commit()` only executes its GraphQL request in this mode; its writes are
not committed until the connection owner commits. Statement and result-set resources are always
closed by the transport. See [JDBC transport configuration](docs/JDBC_TRANSPORT.md).

### Selection planning

The runtime starts from a Viaduct `SelectionSet` and the reflection metadata generated with the
GRTs. It builds the corresponding pg_graphql request, executes it with headers derived from the
current execution context, and converts the returned JSON into the GRT expected by the resolver.

Viaduct and pg_graphql represent connections differently:

```graphql
# Viaduct
fragment Main on GroupCollection {
  nodes { id name }
}

# pg_graphql
fragment Main on GroupConnection {
  edges { node { id name } }
}
```

The runtime recognizes Viaduct connections from their generated types. It translates `nodes` to
`edges { node }` and performs the same conversion for nested connections. Fields named `nodes` or
`edges` on other GraphQL types are left unchanged.

When a connection uses an association table, the generated database GraphQL request selects
through that table. Edge fields come from its row, and the runtime places them in the corresponding
Viaduct edge GRT. Cursor strings and page information returned by pg_graphql pass through
unchanged.

## Mutation Execution

`DbClient.entity<T>()` captures the persistent node type. Its operations receive the typed mutation
resolver context, so the runtime can determine the declared payload without another argument.

The resolver first explicitly converts its Viaduct input to a pg_graphql insert, update, or delete
value. This separate step can be replaced by application-specific conversion when a mutation does
more than directly map input fields. Mutation execution does not inspect a Viaduct input.

For an insert or update, the runtime then:

1. Selects the concrete payload and compatible entity field; validates singular/list cardinality.
2. Creates the generated builder before any database write.
3. Sends the already converted values as pg_graphql variables.
4. Reads returned record IDs and creates concrete Viaduct node references.
5. Sets the selected payload field, initializes `userErrors`, and builds the payload.

A union/interface field is compatible when the concrete entity is one of its possible types.
For an abstract payload root, one compatible concrete payload can be selected automatically.
Several compatible payloads require `payloadType`; several compatible fields require
`entityField`. Batch operations require a list-valued field. A delete may omit the entity field,
but an abstract delete payload with no matching field requires an explicit concrete payload.

Mutation execution handles one persistent node type and one pg_graphql collection mutation at a
time. Input encoding can encode nested Viaduct inputs, maps, and collections as GraphQL variable
values, but encoding a value does not make it a separate database operation. pg_graphql accepts a
nested value only when that value is part of the selected table's generated insert or update input.
It does not interpret a nested persistent node as an instruction to insert or update another table.

A `DbTransaction` buffers already converted pg_graphql mutation values in memory. Beginning or
aborting it does not contact pg_graphql. Committing renders every buffered operation as a uniquely
aliased top-level field with unique variables and sends one GraphQL mutation request. Operation
handles associate each returned payload with the call that added it. The result form retains
partial root data and upstream GraphQL errors.

`DbClient.transaction(ctx) { ... }` owns the buffer lifecycle for the common case. Its restricted
scope exposes entity operations without exposing commit or abort. It commits after the block
returns and aborts when the block throws. Its result retains the value returned by the block, which
can contain operation handles needed for database-result lookup. Explicit `beginTransaction()`,
`commitResult()`, and `abort()` remain available when the application needs result-form errors or
manual control. Explicit transaction state and buffered-operation access are synchronized.

Because all GraphQL variables are supplied at the start of that request, operations cannot consume
earlier returned values. Related inserts use client-created UUIDs. The transaction has explicit
open, committing, committed, aborted, and failed states and cannot be reused after commit, abort,
or failure.

pg_graphql resolves the combined mutation within the database request. PostgreSQL commits the
request when every operation succeeds and rolls it back when the request fails; it does not keep a
database transaction open between `beginTransaction()` and `commit()`.

Relationships are written through their foreign-key ID fields. Creating several related node types
requires separate entity operations and IDs known before the relationship row is inserted. Batch
insert writes several rows of the same node type; it is not recursive object-graph persistence.
Batch update and delete also remain limited to the selected node type.

Updates preserve omitted fields and send an explicitly supplied null as null. Deletes do not add
cascade behavior; only constraints already defined by the application database can cascade a
delete. The entity API does not provide upsert.

A delete payload may have no matching node field. Multiple matching fields require `entityField`.
`insertBatch` uses pg_graphql's list insert. Batch update and delete apply each
independently identified input and combine the returned records into the declared payload.

Update and delete require a generated Viaduct input containing an ID field whose `@idOf` target is
the selected persistent node type. An ID supplied as a separate mutation argument is not inspected.
When exactly one field matches, it is selected automatically. No match fails because a row cannot
be identified. More than one match also fails rather than treating every matching relationship as
part of the filter; the resolver must supply `identifierField` to the conversion function. That field is validated
against the input type, used for the `uuidId` filter, and omitted from the updated values.

The same explicit field name applies to every input in a batch. Batch insert uses one pg_graphql
operation; batch update and delete execute one operation for each independently identified input.
The entity API throws on pg_graphql errors and does not translate them to application `userErrors`.
Resolvers that need explicit filters, partial data, or structured errors use
`PgGraphqlMutationClient` directly.

The explicit mutation conversion uses the same value encoder as filters and other pg_graphql
values. It handles existing JSON, Viaduct inputs, global IDs, maps, collections, and scalars.

## Hibernate and Liquibase

The generated HBM uses `ViaductImplicitNamingStrategy` and `ViaductPhysicalNamingStrategy` by
default. The physical strategy pluralizes table names, converts column names to snake case, and
maps the generated internal ID to `_uuid_id`.

Applications that need to change naming, customize Hibernate metadata, or replace the generated
HBM should follow [Custom configuration](docs/CUSTOM_CONFIGURATION.md).

The plugin exposes a Liquibase reference database using:

```text
hibernate:viaduct:<path-to-descriptor.yaml>
```

The descriptor contains the mapping path, classpath, managed entity names, naming strategies,
dialect, customizers, and Hibernate settings. Gradle tasks create and remove it automatically.

This is a Liquibase reference database, not an application runtime ORM or JDBC driver. An
application that uses pg_graphql does not need Hibernate in its production runtime.

## Authorization Boundary

Generated metadata enables PostgreSQL row-level security but does not define application
authorization policies. A normal Viaduct application keeps the pg_graphql endpoint behind its
backend and uses checker executors before returning persistent fields.

If untrusted clients can reach pg_graphql directly, the application must define the required
database grants and RLS policies.

## Development and Publishing

Run all checks with:

```bash
./gradlew check
```

Publish both modules to an isolated local repository with:

```bash
./gradlew publish \
  -PisolatedRepository=/tmp/viaduct-persistence-repository
```

Release publications use in-memory PGP credentials supplied through `signingKeyId`, `signingKey`,
and `signingPassword`. Snapshot publishing uses Maven Central Portal credentials and does not
require signing.

## Retryable transactions

Retryable transactions are an opt-in extension of buffered transactions, not a DBOS dependency.
The table is part of the generated Hibernate mapping. Its default location is
`persistence_private.transaction_records`; the execution overlay derives actual table and column
names from Hibernate metadata, including custom naming strategies. Only PostgreSQL-specific
functions and grants are generated from a SQL template.

For an identified transaction, the runtime freezes the existing `PreparedTransaction` document
and variables and wraps them in one `pgPersistenceExecuteTransaction` mutation. The PostgreSQL
function calls the public `graphql.resolve` API locally. There is one HTTP request per attempt
and one database transaction for all business writes and the saved response.

The function takes a transaction-scoped advisory lock for the database role, trusted scope, and
operation ID. If another attempt holds it, the function returns busy and the client retries within
its budget. After obtaining the lock, a separate READ COMMITTED statement checks the operation
record. Identical requests receive the saved response; different requests with the same key are
rejected. The unique primary key is the complete encoded identity, not the advisory-lock hash.
Hash collisions only cause additional waiting/retries.

The inner pg_graphql call and result storage share a PostgreSQL exception block. An inner GraphQL
error causes that block to roll back, even when `graphql.resolve` returns an error envelope instead
of throwing. Partial data from rolled-back writes is not exposed as committed data. A failure to
save the response rolls back the business writes too. The execution function uses invoker permissions;
it does not bypass existing database grants or RLS. Recursive calls are rejected.

Retries never rerun resolver logic or the transaction lambda. A versioned `DbPreparedTransaction`
stores the operation ID, trusted scope, document, variables, and result-operation count; it stores
no credentials, resolver context, or lambda. Results are decoded using the existing operation aliases.
The original database response is recovered, not a complete Viaduct payload or newly fetched rows.

Connection loss and timeout can leave commit unknown. A valid committed response remains a confirmed
commit even if local decoding fails. A rejected request does not itself prove that an earlier
attempt never committed. Lookup returning no row can mean the previous request is still running.
Cancellation stops local retries but cannot undo a committed database operation.

Automatic retries cover lost or malformed responses, a busy operation lock, and structured SQLSTATE
`40001`, `40P01`, or `55P03` failures from the wrapper. Inner pg_graphql errors are returned to the
caller without guessing retryability from their message text; pg_graphql may omit the original
SQLSTATE, so not every transient database error can be retried automatically.

Zero-row updates/deletes remain successful pg_graphql operations. Inputs must all be known before
submission; earlier results cannot supply later variables. External GitHub/Discord effects,
automatic reversal, arbitrary PostgreSQL functions, and workflow scheduling are outside this feature.
DBOS can persist a prepared request and resume it, but is not involved in executing this database
transaction. See [deployment and retention requirements](docs/CUSTOM_CONFIGURATION.md#retryable-transactions).

### Testing retryable transactions

The plugin's retry integration tests require an isolated local PostgreSQL database named
`kan22_retry_tests` with the `pg_graphql` extension installed. Set `PG_RETRY_JDBC_URL` and
`PG_RETRY_PASSWORD` for that database; the local defaults are port 55322 and password `postgres`.
The tests create and remove their Hibernate-mapped tables, so never point them at application data.
They execute real `graphql.resolve` calls in Supabase PostgreSQL through JDBC, with a simulated HTTP
transport to test lost responses. They do not test the Supabase HTTP gateway.

```sh
./gradlew :plugin:test --tests '*RetryableTransaction*' \
  :runtime:test --tests '*RetryableTransactionTest'
```
