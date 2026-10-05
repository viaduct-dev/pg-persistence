# Custom Configuration

PG Persistence generates Hibernate metadata only to describe the PostgreSQL model used by
generated SQL and pg_graphql. Most applications should use its defaults. The settings below change
that generated database description and should be covered by application migration tests.

## Use a different assembled schema directory

The plugin uses the output of `assembleViaductCentralSchema` when that task exists. In a module
without that task, it uses `src/main/viaduct/schema`.

Override the directory only when the assembled Viaduct schema is written somewhere else:

```kotlin
viaductPgPersistence {
    centralSchemaDirectory.set(layout.buildDirectory.dir("custom/central-schema"))
}
```

The directory must contain the complete schema from which persistent `Node` types should be
discovered. Pointing it at only part of an application's schema can omit tables or relationships
from generated output.

## Change database names

The default physical naming strategy pluralizes table names, converts names to snake case, and
maps the generated internal ID to `_uuid_id`. Association tables use the same default schema and
naming strategy as persistent node tables.

Supply a Hibernate `ImplicitNamingStrategy` or `PhysicalNamingStrategy` implementation by class
name:

```kotlin
viaductPgPersistence {
    implicitNamingStrategyClassName.set("com.example.CustomImplicitNamingStrategy")
    physicalNamingStrategyClassName.set("com.example.CustomPhysicalNamingStrategy")
}
```

Each class must be available on the application's build classpath and have a no-argument
constructor. A physical naming strategy can recognize generated association-table names and rename
them. Naming changes affect snapshots, diffs, PostgreSQL SQL, and pg_graphql metadata. Changing a
strategy after tables exist requires an application migration; the plugin does not infer renames.

## Customize Hibernate metadata

Use a metadata customizer when a supported Hibernate `MetadataBuilder` setting cannot be expressed
through the persistence policy or naming strategies:

```kotlin
class ApplicationMetadataCustomizer : HibernateMetadataCustomizer {
    override fun customize(metadataBuilder: MetadataBuilder) {
        // Apply application-owned Hibernate metadata configuration.
    }
}
```

Register one or more customizers by class name:

```kotlin
viaductPgPersistence {
    metadataCustomizerClassNames.add("com.example.ApplicationMetadataCustomizer")
}
```

Each class must implement `HibernateMetadataCustomizer`, be available on the build classpath, and
have a no-argument constructor. Customizers run after the plugin applies its naming strategies and
before Hibernate builds the metadata used by snapshot and diff tasks. Registration order is
preserved.

## Replace the generated HBM

For complete control over the Hibernate mapping, provide an HBM XML file:

```kotlin
viaductPgPersistence {
    replacementHbmXml.set(layout.projectDirectory.file("config/persistence.hbm.xml"))
}
```

The file completely replaces the generated HBM; it is not merged with it. It must declare exactly
the persistent entities and generated association entities expected from the GraphQL schema.
Generation validates the managed entity names, but the application remains responsible for table,
column, relationship, ID, and nullability compatibility.

A replacement affects every generated database file and the Liquibase reference database. Review
the resulting PostgreSQL and pg_graphql SQL and test upgrades from the application's existing
schema.

## Use a different persistence policy file

The persistence policy defaults to `src/main/viaduct/persistence.yaml`. To move it:

```kotlin
viaductPgPersistence {
    persistenceConfigFile.set(layout.projectDirectory.file("config/persistence.yaml"))
}
```

This changes only the file location. The supported YAML keys and their behavior remain those
documented in the main [README](../README.md#configure-persistence-policy).

## Retryable transactions

Enable the feature in the existing schema-adjacent `persistence.yaml`:

```yaml
retryableTransactions: true
```

The generated HBM mapping includes `PgPersistenceTransactionRecord`. Hibernate manages its
columns and primary key, and the usual snapshot/diff workflow produces its table migration.
Apply generated PostgreSQL prerequisites, review/apply the Hibernate table migration, then apply
the generated pg_graphql metadata overlay. `pg-graphql-transactions.sql` is also emitted for
inspection; its contents are included in the normal combined overlays. Do not apply it twice.
The application does not author the table DDL or transaction function.

If you replace the generated HBM, retain the generated `PgPersistenceTransactionRecord` entity
mapping in the replacement file. The same managed-entity validation applies to this library table.

Installation creates the NOLOGIN role `persistence_executor`; the migration role needs permission
to create that role on first installation. Grant membership only to a trusted backend database
role. For a Supabase backend deliberately using `service_role`:

```sql
GRANT persistence_executor TO service_role;
```

Never grant it to `anon`, `authenticated`, or a browser-facing role. Keep the record table's
`persistence_private` schema out of the API's exposed schemas and search path. Applications using
custom naming strategies must keep its resulting schema private as well. Review database default
privileges: no untrusted role may read or write the operation records.

Configure the runtime with `DbRetryableTransactions`. Its required `DbTransactionIdentity`
callback receives the execution context and must return a stable tenant/caller scope derived from
trusted authentication. Include any database-role identity relevant to the application; changing
the database role must not silently move an old prepared request into a new operation namespace.
This is an application integration point, like `DbRequestHeaders`, not a new Viaduct context API.

Pass the settings when constructing the client. Here, `authenticatedTransactionScope` is an
application function that derives that trusted identity:

```kotlin
val dbClient = DbClient(
    httpClient,
    graphqlEndpoint,
    requestHeaders = requestHeaders,
    retryableTransactions = DbRetryableTransactions(
        identity = DbTransactionIdentity { ctx -> authenticatedTransactionScope(ctx) },
        maxAttempts = 3,
        timeoutMillis = 30_000,
    ),
)
```

The callback must not copy a request argument or incoming scope header without authentication.
The library supplies `x-pg-persistence-scope` itself and checks the callback again on each attempt.
The pg_graphql HTTP service must forward request headers to PostgreSQL's `request.headers` setting,
as Supabase does. Applications still perform authorization through their checker executors before
execution, lookup, or recovery.

`DbRetryableTransactions` defaults to three attempts, a 30-second total deadline, and a 100-ms
initial retry delay. Delays use backoff with jitter, capped at ten seconds. Do not layer independent
unbounded HTTP retries over these settings. Calls without an operation ID retain ordinary
transaction behavior. Missing runtime configuration or server support fails explicitly.

Operation IDs and scopes are limited to 256 UTF-8 bytes each; requests are limited to 1 MiB of
PostgreSQL JSONB text. Only READ COMMITTED and library-generated insert/update/delete transactions
are supported. Do not expose an endpoint accepting arbitrary serialized prepared requests.

Operation records contain sensitive inputs and results. Retain them for at least the application's
retry/recovery window. There is no automatic deletion: arrange a reviewed maintenance policy using
the mapped completion timestamp. Once a record is deleted, its ID may execute again; do not replay
expired operations. Disabling generation does not safely delete old records or revoke installed
functions; retire the feature through an explicit application migration.
