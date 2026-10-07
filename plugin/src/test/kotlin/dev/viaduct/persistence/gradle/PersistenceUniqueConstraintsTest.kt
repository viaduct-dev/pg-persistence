package dev.viaduct.persistence.gradle

import dev.viaduct.persistence.hibernate.FreshSchemaSqlWriter
import dev.viaduct.persistence.hibernate.HibernateMetadataBootstrap
import dev.viaduct.persistence.hibernate.HibernateMetadataConfigurationFactory
import dev.viaduct.persistence.hibernate.HibernateMetadataConfigurationInput
import dev.viaduct.persistence.hibernate.HibernateSchemaModelWriter
import dev.viaduct.persistence.hibernate.PersistenceModelYaml
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PersistenceUniqueConstraintsTest {
    @Test
    fun `single and composite keys reach Hibernate and fresh schema SQL`() {
        fixture(
            """
            type Person implements Node { id: ID!, username: String! }
            type Membership implements Node {
              id: ID!, person: Person!, label: String!
            }
        """,
            """
            types:
              Person:
                unique: [[username]]
              Membership:
                unique: [[person, label]]
            """,
        ) { schema, config, root ->
            val model = PersistenceSchemaModelLoader.build(schema, config)
            val generated = root.resolve("generated")
            HibernateSchemaModelWriter().write(model, generated)
            val configuration =
                HibernateMetadataConfigurationFactory.create(
                    HibernateMetadataConfigurationInput(
                        mappingFile = generated.resolve("resources/META-INF/viaduct-persistence.hbm.xml"),
                        classpath = System.getProperty("java.class.path").split(File.pathSeparator).map(::File),
                        semanticModel = model,
                    ),
                )
            HibernateMetadataBootstrap.build(configuration).use { handle ->
                val membership = handle.metadata.getEntityBinding("Membership")
                assertEquals(
                    setOf("label", "person_id"),
                    membership.table.uniqueKeys.values
                        .single()
                        .columns
                        .map { it.name }
                        .toSet(),
                )
                val ddl = root.resolve("create.sql")
                FreshSchemaSqlWriter.write(handle.metadata, ddl)
                assertTrue(ddl.readText().contains("unique (username)", ignoreCase = true))
            }
            assertEquals(model, PersistenceModelYaml.fromYaml(PersistenceModelYaml.toYaml(model)))
        }
    }

    @Test
    fun `keys can reference fields declared on type extensions`() {
        fixture(
            """
            type Person implements Node { id: ID! }
            extend type Person { username: String! }
        """,
            "types:\n  Person:\n    unique: [[username]]",
        ) { schema, config, _ ->
            assertEquals(
                listOf(listOf("username")),
                PersistenceSchemaModelLoader
                    .build(schema, config)
                    .entities
                    .single()
                    .uniqueKeys,
            )
        }
    }

    @Test
    fun `fresh SQL uses native arrays and unbounded strings`() {
        fixture(
            """
            type Person implements Node { id: ID!, description: String!, tags: [String!]!, scores: [Int!]! }
        """,
        ) { schema, config, root ->
            val model = PersistenceSchemaModelLoader.build(schema, config)
            val generated = root.resolve("generated")
            HibernateSchemaModelWriter().write(model, generated)
            val configuration =
                HibernateMetadataConfigurationFactory.create(
                    HibernateMetadataConfigurationInput(
                        mappingFile = generated.resolve("resources/META-INF/viaduct-persistence.hbm.xml"),
                        classpath = System.getProperty("java.class.path").split(File.pathSeparator).map(::File),
                        semanticModel = model,
                    ),
                )
            HibernateMetadataBootstrap.build(configuration).use { handle ->
                val ddl = root.resolve("create.sql")
                FreshSchemaSqlWriter.write(handle.metadata, ddl)
                val sql = ddl.readText()
                assertTrue(
                    "description text not null" in sql &&
                        "tags text[] not null" in sql &&
                        "scores integer[] not null" in sql,
                )
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "tags", "id", "internalId"])
    fun `rejects fields that cannot form a stored unique key`(field: String) {
        fixture(
            "type Person implements Node { id: ID!, username: String!, tags: [String!]! }",
            "types:\n  Person:\n    unique: [[$field]]",
        ) { schema, config, _ ->
            assertFailsWith<IllegalArgumentException> { PersistenceSchemaModelLoader.build(schema, config) }
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["[]", "[[]]", "[[username, username]]", "username", "[username]", "[[1]]", "[['']]", "null"],
    )
    fun `rejects malformed uniqueness policies`(keys: String) {
        fixture(
            "type Person implements Node { id: ID!, username: String! }",
            "types:\n  Person:\n    unique: $keys",
        ) { schema, config, _ ->
            assertFailsWith<IllegalArgumentException> { PersistenceSchemaModelLoader.build(schema, config) }
        }
    }

    @Test
    fun `rejects unique keys on unknown types`() {
        fixture(
            "type Person implements Node { id: ID!, username: String! }",
            "types:\n  Missing:\n    unique: [[username]]",
        ) { schema, config, _ ->
            assertFailsWith<IllegalArgumentException> { PersistenceSchemaModelLoader.build(schema, config) }
        }
    }

    @Test
    fun `rejects unique keys on excluded types`() {
        fixture(
            "type Person implements Node { id: ID!, username: String! }",
            "types:\n  Person:\n    excluded: true\n    unique: [[username]]",
        ) { schema, config, _ ->
            assertFailsWith<IllegalArgumentException> { PersistenceSchemaModelLoader.build(schema, config) }
        }
    }

    @Test
    fun `independent and duplicate keys are normalized deterministically`() {
        fixture(
            "type Person implements Node { id: ID!, username: String!, label: String! }",
            "types:\n  Person:\n    unique: [[username, label], [label, username], [username]]",
        ) { schema, config, _ ->
            assertEquals(
                listOf(listOf("label", "username"), listOf("username")),
                PersistenceSchemaModelLoader
                    .build(schema, config)
                    .entities
                    .single()
                    .uniqueKeys,
            )
        }
    }

    @Test
    fun `ordinary fields have no uniqueness constraint without policy`() {
        fixture("type Person implements Node { id: ID!, username: String! }") { schema, config, _ ->
            assertEquals(
                emptyList(),
                PersistenceSchemaModelLoader
                    .build(schema, config)
                    .entities
                    .single()
                    .uniqueKeys,
            )
        }
    }

    private fun fixture(
        sdl: String,
        policy: String = "",
        block: (File, File, File) -> Unit,
    ) {
        val root = Files.createTempDirectory("schema-unique").toFile()
        try {
            val schema = Files.createDirectory(root.toPath().resolve("schema")).toFile()
            schema.resolve("Model.graphqls").writeText(sdl)
            val config = root.resolve("pg-persistence.yaml").apply { writeText(policy.trimIndent()) }
            block(schema, config, root)
        } finally {
            root.deleteRecursively()
        }
    }
}
