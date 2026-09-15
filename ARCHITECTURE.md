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

1. Sends the already converted values as pg_graphql variables.
2. Reads returned record IDs.
3. Creates Viaduct node references.
4. Finds the single payload field whose type matches the persistent node.
5. Builds that payload and initializes `userErrors`.

Mutation execution handles one persistent node type and one pg_graphql collection mutation at a
time. Input encoding can encode nested Viaduct inputs, maps, and collections as GraphQL variable
values, but encoding a value does not make it a separate database operation. pg_graphql accepts a
nested value only when that value is part of the selected table's generated insert or update input.
It does not interpret a nested persistent node as an instruction to insert or update another table.

Relationships are written through their foreign-key ID fields. Creating several related node types
requires separate entity operations and IDs known before the relationship row is inserted. Batch
insert writes several rows of the same node type; it is not recursive object-graph persistence.
Batch update and delete also remain limited to the selected node type.

Updates preserve omitted fields and send an explicitly supplied null as null. Deletes do not add
cascade behavior; only constraints already defined by the application database can cascade a
delete. The entity API does not provide upsert.

A delete payload may have no matching node field. Multiple matching fields are rejected as
ambiguous. `insertBatch` uses pg_graphql's list insert. Batch update and delete apply each
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
