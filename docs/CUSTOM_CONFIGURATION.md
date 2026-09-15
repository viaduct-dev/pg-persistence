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
