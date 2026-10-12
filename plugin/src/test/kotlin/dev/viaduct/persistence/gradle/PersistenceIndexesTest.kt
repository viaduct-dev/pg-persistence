package dev.viaduct.persistence.gradle

import dev.viaduct.persistence.hibernate.FreshSchemaSqlWriter
import dev.viaduct.persistence.hibernate.HibernateMetadataBootstrap
import dev.viaduct.persistence.hibernate.HibernateMetadataConfigurationDescriptor
import dev.viaduct.persistence.hibernate.HibernateMetadataConfigurationFactory
import dev.viaduct.persistence.hibernate.HibernateMetadataConfigurationInput
import dev.viaduct.persistence.hibernate.HibernateSchemaModelWriter
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PersistenceIndexesTest {
    @Test
    fun `single composite and foreign key indexes survive descriptors and generate native DDL`() =
        fixture(
            """
            type Person implements Node { id: ID!, username: String!, region: String! }
            type Membership implements Node { id: ID!, person: Person!, label: String! }
            """,
            """
            types:
              Person:
                unique: [[username]]
                indexes: [[region], [region, username], [username, region]]
              Membership:
                indexes: [[person, label]]
            """,
        ) { schema, policy, root ->
            val model = PersistenceSchemaModelLoader.build(schema, policy)
            val generated = root.resolve("generated")
            HibernateSchemaModelWriter().write(model, generated)
            val configuration =
                HibernateMetadataConfigurationFactory.create(
                    HibernateMetadataConfigurationInput(
                        generated.resolve("resources/META-INF/viaduct-persistence.hbm.xml"),
                        System.getProperty("java.class.path").split(File.pathSeparator).map(::File),
                        model,
                    ),
                )
            val descriptor = root.resolve("metadata.yaml")
            HibernateMetadataConfigurationDescriptor.write(configuration, descriptor)
            val restored = HibernateMetadataConfigurationDescriptor.read(descriptor)
            HibernateMetadataBootstrap.build(restored).use { handle ->
                val columns = indexColumns(handle.metadata)
                val ddl = root.resolve("create.sql")
                FreshSchemaSqlWriter.write(handle.metadata, ddl)
                assertEquals(
                    listOf(
                        model,
                        mapOf(
                            "Person" to
                                setOf(
                                    listOf("region") to false,
                                    listOf("region", "username") to false,
                                    listOf("username", "region") to false,
                                ),
                            "Membership" to setOf(listOf("person_id", "label") to false),
                        ),
                        4,
                        true,
                    ),
                    listOf(
                        restored.semanticModel,
                        columns,
                        Regex("(?i)create index").findAll(ddl.readText()).count(),
                        "unique (username)" in ddl.readText(),
                    ),
                )
            }
        }

    @Test
    fun `index declarations preserve field order and deduplicate identical definitions`() =
        fixture(
            "type Person implements Node { id: ID!, username: String!, region: String! }",
            "types:\n  Person:\n    indexes: [[region, username], [username, region], [region, username]]",
        ) { schema, policy, _ ->
            assertEquals(
                listOf(listOf("region", "username"), listOf("username", "region")),
                PersistenceSchemaModelLoader
                    .build(schema, policy)
                    .entities
                    .single()
                    .indexes,
            )
        }

    @Test
    fun `index fields include nullable scalars and idOf fields declared in extensions`() =
        fixture(
            """
            type Person implements Node { id: ID!, username: String }
            type Membership implements Node { id: ID! }
            extend type Membership { personId: ID @idOf(type: "Person"), label: String }
            """,
            "types:\n  Membership:\n    indexes: [[personId, label]]\n  Person:\n    indexes: [[username]]",
        ) { schema, policy, _ ->
            assertEquals(
                mapOf("Membership" to listOf(listOf("personId", "label")), "Person" to listOf(listOf("username"))),
                PersistenceSchemaModelLoader.build(schema, policy).entities.associate { it.graphqlName to it.indexes },
            )
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `index columns use physical naming for idOf fields and index names stay stable`(customNaming: Boolean) =
        fixture(
            """
            type Person implements Node { id: ID! }
            type Membership implements Node { id: ID! }
            extend type Membership { personId: ID @idOf(type: "Person"), label: String }
            """,
            "types:\n  Membership:\n    indexes: [[personId, label]]",
        ) { schema, policy, root ->
            val model = PersistenceSchemaModelLoader.build(schema, policy)
            val generated = root.resolve("generated")
            HibernateSchemaModelWriter().write(model, generated)
            val input =
                HibernateMetadataConfigurationInput(
                    generated.resolve("resources/META-INF/viaduct-persistence.hbm.xml"),
                    System.getProperty("java.class.path").split(File.pathSeparator).map(::File),
                    model,
                )
            val configuration = HibernateMetadataConfigurationFactory.create(input)
            val custom =
                HibernateMetadataConfigurationFactory.create(
                    HibernateMetadataConfigurationInput(
                        generated.resolve("resources/META-INF/viaduct-persistence.hbm.xml"),
                        System.getProperty("java.class.path").split(File.pathSeparator).map(::File),
                        model,
                        physicalNamingStrategyClassName = RetryNamingStrategy::class.java.name,
                    ),
                )
            HibernateMetadataBootstrap.build(configuration).use { original ->
                HibernateMetadataBootstrap.build(if (customNaming) custom else configuration).use { changed ->
                    val originalIndex =
                        original.metadata
                            .getEntityBinding("Membership")
                            .table.indexes.values
                            .single()
                    val changedIndex =
                        changed.metadata
                            .getEntityBinding("Membership")
                            .table.indexes.values
                            .single()
                    val prefix = if (customNaming) "audit_" else ""
                    assertEquals(
                        listOf(originalIndex.name, listOf("${prefix}person_id", "${prefix}label")),
                        listOf(changedIndex.name, changedIndex.selectables.map { it.text }),
                    )
                }
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "tags", "id", "internalId"])
    fun `rejects fields that cannot form a stored secondary index`(field: String) =
        fixture(
            "type Person implements Node { id: ID!, username: String!, tags: [String!]! }",
            "types:\n  Person:\n    indexes: [[$field]]",
        ) { schema, policy, _ ->
            assertFailsWith<IllegalArgumentException> { PersistenceSchemaModelLoader.build(schema, policy) }
        }

    @Test
    fun `rejects resolver backed index fields`() =
        fixture(
            "type Person implements Node { id: ID!, computed: String @resolver }",
            "types:\n  Person:\n    indexes: [[computed]]",
        ) { schema, policy, _ ->
            assertFailsWith<IllegalStateException> { PersistenceSchemaModelLoader.build(schema, policy) }
        }

    @ParameterizedTest
    @ValueSource(
        strings = ["[]", "[[]]", "[[username, username]]", "username", "[username]", "[[1]]", "[['']]", "null"],
    )
    fun `rejects malformed secondary index policies`(indexes: String) =
        fixture(
            "type Person implements Node { id: ID!, username: String! }",
            "types:\n  Person:\n    indexes: $indexes",
        ) { schema, policy, _ ->
            assertFailsWith<IllegalArgumentException> { PersistenceSchemaModelLoader.build(schema, policy) }
        }

    @ParameterizedTest
    @ValueSource(strings = ["Missing", "Person"])
    fun `rejects indexes on unknown or excluded types`(type: String) =
        fixture(
            "type Person implements Node { id: ID!, username: String! }",
            "types:\n  $type:\n" +
                if (type == "Person") {
                    "    excluded: true\n    indexes: [[username]]"
                } else {
                    "    indexes: [[username]]"
                },
        ) { schema, policy, _ ->
            assertFailsWith<IllegalArgumentException> { PersistenceSchemaModelLoader.build(schema, policy) }
        }

    private fun indexColumns(metadata: org.hibernate.boot.Metadata) =
        metadata.entityBindings.associate { binding ->
            binding.entityName to
                binding.table.indexes.values
                    .map { index ->
                        index.selectables.map { it.text } to index.isUnique
                    }.toSet()
        }

    private fun fixture(
        sdl: String,
        yaml: String,
        test: (File, File, File) -> Unit,
    ) {
        val root = Files.createTempDirectory("schema-indexes").toFile()
        try {
            val schema = root.resolve("schema").apply { check(mkdirs()) }
            schema.resolve("Model.graphqls").writeText(sdl.trimIndent())
            val policy = root.resolve("pg-persistence.yaml").apply { writeText(yaml.trimIndent()) }
            test(schema, policy, root)
        } finally {
            root.deleteRecursively()
        }
    }
}
