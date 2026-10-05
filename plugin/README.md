# Gradle Plugin

The `dev.viaduct.pg-persistence` plugin generates a Hibernate and PostgreSQL persistence
model from an assembled Viaduct GraphQL schema.

The plugin is the build-time half of the persistence integration. Pair it with
`dev.viaduct.persistence:runtime` to read and write application data through pg_graphql.

```kotlin
plugins {
    kotlin("jvm")
    id("com.airbnb.viaduct.application-gradle-plugin") version "<viaduct-version>"
    id("dev.viaduct.pg-persistence") version "0.1.0-SNAPSHOT"
}

```

The plugin generates Hibernate XML mapping files and PostgreSQL SQL files that define tables,
constraints, and pg_graphql configuration. It does not generate entity source code.

Run:

```bash
./gradlew buildViaductEffectiveModel
./gradlew hibernateSchemaDiff
```

The generated PostgreSQL migration input and repeatable pg_graphql metadata are written to:

```text
build/generated/viaduct-effective-model/META-INF/postgresql-migration.sql
build/generated/viaduct-effective-model/META-INF/pg-graphql-metadata.sql
```

See the repository [README](../README.md) for repository configuration, GraphQL conventions,
custom naming strategies, generated artifacts, and migration workflow.
