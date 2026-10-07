package dev.viaduct.persistence.gradle

import dev.viaduct.persistence.hibernate.FreshSchemaSqlWriter
import dev.viaduct.persistence.hibernate.HibernateMetadataBootstrap
import dev.viaduct.persistence.hibernate.HibernateMetadataConfigurationFactory
import dev.viaduct.persistence.hibernate.HibernateMetadataConfigurationInput
import dev.viaduct.persistence.hibernate.HibernateSchemaModelWriter
import dev.viaduct.persistence.hibernate.PersistenceModelYaml
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SchemaUniqueConstraintsTest {
    @Test
    fun `single and composite keys reach Hibernate and fresh schema SQL`() {
        fixture(
            """
            type Person implements Node @unique(fields: ["username"]) { id: ID!, username: String! }
            type Membership implements Node @unique(fields: ["person", "label"]) {
              id: ID!, person: Person!, label: String!
            }
        """,
        ) { schema, root ->
            val model = PersistenceSchemaModelLoader.build(schema, null)
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
    fun `keys on type extensions are included`() {
        fixture(
            """
            type Person implements Node { id: ID!, username: String! }
            extend type Person @unique(fields: ["username"])
        """,
        ) { schema, _ ->
            assertEquals(
                listOf(listOf("username")),
                PersistenceSchemaModelLoader
                    .build(schema, null)
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
        ) { schema, root ->
            val model = PersistenceSchemaModelLoader.build(schema, null)
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

    @Test
    fun `rejects unknown fields`() {
        fixture("type Person implements Node @unique(fields: [\"missing\"]) { id: ID! }") { schema, _ ->
            assertFailsWith<IllegalArgumentException> { PersistenceSchemaModelLoader.build(schema, null) }
        }
    }

    @Test
    fun `rejects non stored collections`() {
        val sdl = "type Person implements Node @unique(fields: [\"tags\"]) { id: ID!, tags: [String!]! }"
        fixture(sdl) { schema, _ ->
            assertFailsWith<IllegalArgumentException> { PersistenceSchemaModelLoader.build(schema, null) }
        }
    }

    @Test
    fun `rejects empty and repeated key fields`() {
        for (fields in listOf("[]", "[\"username\", \"username\"]")) {
            val sdl = "type Person implements Node @unique(fields: $fields) { id: ID!, username: String! }"
            fixture(sdl) { schema, _ ->
                assertFailsWith<IllegalArgumentException> { PersistenceSchemaModelLoader.build(schema, null) }
            }
        }
    }

    private fun fixture(
        sdl: String,
        block: (File, File) -> Unit,
    ) {
        val root = Files.createTempDirectory("schema-unique").toFile()
        try {
            val schema = Files.createDirectory(root.toPath().resolve("schema")).toFile()
            schema.resolve("Model.graphqls").writeText(sdl)
            block(schema, root)
        } finally {
            root.deleteRecursively()
        }
    }
}
