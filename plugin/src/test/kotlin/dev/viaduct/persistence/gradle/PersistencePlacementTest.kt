package dev.viaduct.persistence.gradle

import dev.viaduct.persistence.hibernate.HbmToManyMapping
import dev.viaduct.persistence.hibernate.HibernateMetadataBootstrap
import dev.viaduct.persistence.hibernate.HibernateMetadataConfigurationFactory
import dev.viaduct.persistence.hibernate.HibernateMetadataConfigurationInput
import dev.viaduct.persistence.hibernate.HibernateSchemaModelWriter
import dev.viaduct.persistence.hibernate.PersistenceModelToHbmMapper
import dev.viaduct.persistence.hibernate.PersistenceModelYaml
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PersistencePlacementTest {
    @TempDir
    private lateinit var directory: File

    @Test
    fun `references and extensions preserve original type placement and reciprocal join ownership`() {
        val (schema, artifacts) = fixture()
        val model = PersistenceSchemaModelLoader.build(schema, null, modulePlacementArtifacts = artifacts)
        val mappings = PersistenceModelToHbmMapper.map(model).entities.associateBy { it.entityName }
        assertEquals(
            mapOf("Person" to "people", "Reviewer" to "people", "Team" to "orders", "PublicRecord" to null),
            mappings.mapValues { it.value.schemaName },
        )
        val joins = mappings.values.flatMap { it.attributes }.filterIsInstance<HbmToManyMapping>()
        assertEquals(setOf("people"), joins.map { it.joinSchemaName }.toSet())
        assertEquals(1, joins.map { it.joinTableName }.distinct().size)
        assertEquals("com.example.people", model.entities.single { it.graphqlName == "Person" }.owningModule)
    }

    @Test
    fun `hibernate boot preserves qualified cross-schema foreign keys`() {
        val (schema, artifacts) = fixture()
        val policy =
            directory.resolve("pg-persistence.yaml").apply {
                writeText("types:\n  Person:\n    indexes: [[name, extra]]\n")
            }
        val model = PersistenceSchemaModelLoader.build(schema, policy, modulePlacementArtifacts = artifacts)
        assertEquals(listOf(listOf("name", "extra")), model.entities.single { it.graphqlName == "Person" }.indexes)
        assertEquals(model, PersistenceModelYaml.fromYaml(PersistenceModelYaml.toYaml(model)))
        val generated = directory.resolve("generated")
        HibernateSchemaModelWriter().write(model, generated)
        val configuration =
            HibernateMetadataConfigurationFactory.create(
                HibernateMetadataConfigurationInput(
                    generated.resolve("resources/META-INF/viaduct-persistence.hbm.xml"),
                    System
                        .getProperty("java.class.path")
                        .split(File.pathSeparator)
                        .map(::File)
                        .filter(File::exists),
                    model,
                ),
            )
        HibernateMetadataBootstrap.build(configuration).use { handle ->
            val person = handle.metadata.getEntityBinding("Person").table
            assertEquals("people", person.schema)
            assertEquals(
                listOf("name", "extra"),
                person.indexes.values
                    .single()
                    .columns
                    .map { it.name },
            )
            val team = handle.metadata.getEntityBinding("Team").table
            val lead = team.foreignKeys.values.single { it.referencedEntityName == "Reviewer" }
            assertEquals("orders" to "people", team.schema to lead.referencedTable.schema)
            val join =
                handle.metadata.collectionBindings
                    .first { it.role == "Person.teams" }
                    .collectionTable
            assertEquals("people", join.schema)
            assertEquals(
                setOf("people", "orders"),
                join.foreignKeys.values
                    .map { it.referencedTable.schema }
                    .toSet(),
            )
        }
    }

    @Test
    fun `conflicting ownership and placement fail before mapping generation`() {
        val (schema, artifacts) = fixture()
        val conflicting = metadata("duplicate.json-dir", "com.example.other", "orders", listOf("Person"))
        assertTrue(
            assertFailsWith<IllegalArgumentException> {
                PersistenceSchemaModelLoader.build(schema, null, modulePlacementArtifacts = artifacts + conflicting)
            }.message.orEmpty().contains("Ambiguous ownership"),
        )
        val changed = metadata("changed", "com.example.people", "different", listOf("Person"))
        assertTrue(
            assertFailsWith<IllegalArgumentException> {
                PersistenceSchemaModelLoader.build(schema, null, modulePlacementArtifacts = artifacts + changed)
            }.message.orEmpty().contains("Conflicting database placement"),
        )
    }

    @Test
    fun `explicit module schema works locally while omitted config preserves default mappings`() {
        val schema = directory.resolve("local").apply { check(mkdirs()) }
        schema.resolve("Model.graphqls").writeText("type Person implements Node { id: ID!, name: String }")
        val policy = directory.resolve("pg-persistence.yaml").apply { writeText("databaseSchema: people\n") }
        assertEquals(
            "people",
            PersistenceSchemaModelLoader
                .build(schema, policy)
                .entities
                .single()
                .databaseSchema,
        )
        assertEquals(
            null,
            PersistenceSchemaModelLoader
                .build(schema, null)
                .entities
                .single()
                .databaseSchema,
        )
    }

    @Test
    fun `shared schemas public placement serialization and discovery order remain stable`() {
        val (schema, artifacts) = fixture()
        val shared = metadata("shared", "com.example.public", "public", listOf("PublicRecord"))
        val model = PersistenceSchemaModelLoader.build(schema, null, modulePlacementArtifacts = artifacts + shared)
        assertEquals(model, PersistenceModelYaml.fromYaml(PersistenceModelYaml.toYaml(model)))
        assertEquals(
            model,
            PersistenceSchemaModelLoader.build(
                schema,
                null,
                modulePlacementArtifacts = (artifacts + shared).reversed(),
            ),
        )
        assertEquals(null, model.entities.single { it.graphqlName == "PublicRecord" }.databaseSchema)
        assertEquals("com.example.public", model.entities.single { it.graphqlName == "PublicRecord" }.owningModule)
        val other = metadata("another", "com.example.other", "people", listOf("PublicRecord"))
        val sharedModel =
            PersistenceSchemaModelLoader.build(schema, null, modulePlacementArtifacts = artifacts.dropLast(1) + other)
        assertEquals("people", sharedModel.entities.single { it.graphqlName == "PublicRecord" }.databaseSchema)
    }

    @Test
    fun `schema splitting rejects a missing module artifact instead of defaulting to public`() {
        val (schema, artifacts) = fixture()
        val failure =
            assertFailsWith<IllegalArgumentException> {
                PersistenceSchemaModelLoader.build(schema, null, modulePlacementArtifacts = artifacts.dropLast(1))
            }
        assertContains(failure.message.orEmpty(), "Missing persistence placement metadata for PublicRecord")
        assertContains(failure.message.orEmpty(), "modules using public must also publish ownership metadata")
    }

    @Test
    fun `schema splitting rejects incomplete type ownership and empty module metadata`() {
        val (schema, artifacts) = fixture()
        val incomplete = metadata("incomplete", "com.example.people", "people", listOf("Person"))
        assertContains(
            assertFailsWith<IllegalArgumentException> {
                PersistenceSchemaModelLoader.build(
                    schema,
                    null,
                    modulePlacementArtifacts = artifacts.drop(1) + incomplete,
                )
            }.message.orEmpty(),
            "Missing persistence placement metadata for Reviewer",
        )
        val empty = metadata("empty", "com.example.empty", "people", emptyList())
        assertContains(
            assertFailsWith<IllegalArgumentException> {
                PersistenceSchemaModelLoader.build(schema, null, modulePlacementArtifacts = listOf(empty))
            }.message.orEmpty(),
            "Missing persistence placement metadata",
        )
    }

    @Test
    fun `public-only legacy types remain compatible without module metadata`() {
        val (schema, _) = fixture()
        val model = PersistenceSchemaModelLoader.build(schema, null)
        assertTrue(model.entities.all { it.databaseSchema == null })
    }

    @Test
    fun `remaining public metadata cannot hide a missing schema-split module`() {
        val (schema, artifacts) = fixture()
        assertContains(
            assertFailsWith<IllegalArgumentException> {
                PersistenceSchemaModelLoader.build(schema, null, modulePlacementArtifacts = artifacts.takeLast(1))
            }.message.orEmpty(),
            "Missing persistence placement metadata",
        )
    }

    @Test
    fun `empty assembled metadata directory cannot silently restore legacy placement`() {
        val (schema, _) = fixture()
        check(schema.resolve(".persistence").mkdirs())
        assertContains(
            assertFailsWith<IllegalArgumentException> {
                PersistenceSchemaModelLoader.build(schema, null)
            }.message.orEmpty(),
            "Missing persistence placement metadata",
        )
    }

    @Test
    fun `nested edge associations inherit canonical module schema`() {
        val schema = directory.resolve("nested-schema").apply { check(mkdirs()) }
        schema.resolve("Model.graphqls").writeText(
            """
            type Person implements Node { id: ID!, teams: TeamConnection }
            type Team implements Node { id: ID! }
            type Reviewer implements Node { id: ID! }
            type TeamConnection { edges: [TeamEdge!]!, nodes: [Team!]! }
            type TeamEdge { cursor: String!, node: Team!, reviewers: ReviewerConnection }
            type ReviewerConnection { edges: [ReviewerEdge!]!, nodes: [Reviewer!]! }
            type ReviewerEdge { cursor: String!, node: Reviewer!, score: Int }
            """.trimIndent(),
        )
        val artifacts =
            listOf(
                metadata("nested-people", "com.example.people", "people", listOf("Person", "Reviewer")),
                metadata("nested-orders", "com.example.orders", "orders", listOf("Team", "TeamConnection", "TeamEdge")),
            )
        val model = PersistenceSchemaModelLoader.build(schema, null, modulePlacementArtifacts = artifacts)
        val mappings = PersistenceModelToHbmMapper.map(model).entities
        assertEquals("people", mappings.single { it.entityName == "PersonTeamsAssociation" }.schemaName)
        val nested =
            mappings
                .single { it.entityName == "PersonTeamsAssociation" }
                .attributes
                .filterIsInstance<HbmToManyMapping>()
                .single()
        assertEquals("people", nested.joinSchemaName)
    }

    private fun fixture(): Pair<File, List<File>> {
        val schema = directory.resolve("schema").apply { check(mkdirs()) }
        schema.resolve("People.graphqls").writeText(
            "type Person implements Node { id: ID!, name: String, teams: [Team!] }\n" +
                "type Reviewer implements Node { id: ID!, name: String }",
        )
        schema.resolve("Orders.graphqls").writeText(
            "type Team implements Node { id: ID!, members: [Person!], lead: Reviewer }\n" +
                "extend type Person { extra: String }\n" +
                "type PublicRecord implements Node { id: ID! }",
        )
        return schema to
            listOf(
                metadata("people", "com.example.people", "people", listOf("Person", "Reviewer")),
                metadata("orders", "com.example.orders", "orders", listOf("Team")),
                metadata("public", "com.example.public", "public", listOf("PublicRecord")),
            )
    }

    private fun metadata(
        name: String,
        module: String,
        schema: String,
        types: List<String>,
    ): File =
        directory.resolve(name).apply { check(mkdirs()) }.resolve(PersistencePlacementRegistrar.FILE_NAME).apply {
            writeText(
                buildJsonObject {
                    put("version", 1)
                    put("module", module)
                    put("databaseSchema", schema)
                    put("types", JsonArray(types.map(::JsonPrimitive)))
                }.toString(),
            )
        }
}
