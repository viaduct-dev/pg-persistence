package dev.viaduct.persistence.gradle

import dev.viaduct.persistence.hibernate.HibernateSchemaModelWriter
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PersistenceSchemaModelLoaderTest {
    @Test
    fun `denylist subtracts from discovered persistent types`() {
        val fixture = fixture()
        fixture.config.writeText("types:\n  AuditEvent:\n    excluded: true\n")

        val model = PersistenceSchemaModelLoader.build(fixture.schemaDirectory, fixture.config)

        assertEquals(listOf("Group"), model.entities.map { it.graphqlName })
        assertTrue(model.entities.single().generatedGlobalId)
    }

    @Test
    fun `denylist is the only exception to Node persistence validation`() {
        val fixture = fixture(auditField = "external: String @resolver")
        fixture.config.writeText("types:\n  AuditEvent:\n    excluded: true\n")

        val model = PersistenceSchemaModelLoader.build(fixture.schemaDirectory, fixture.config)

        assertEquals(listOf("Group"), model.entities.map { it.graphqlName })
    }

    @Test
    fun `validates every Node when YAML does not deny it`() {
        val fixture = fixture(auditField = "external: String @resolver")

        val failure =
            assertFailsWith<IllegalStateException> {
                PersistenceSchemaModelLoader.build(fixture.schemaDirectory, null)
            }

        assertTrue(failure.message!!.contains("Persistent Node 'AuditEvent'"))
    }

    @Test
    fun `semantic non-null YAML overrides persistence nullability`() {
        val fixture = fixture()
        fixture.config.writeText("types:\n  Group:\n    fields:\n      name:\n        semanticNotNull: true\n")

        val model = PersistenceSchemaModelLoader.build(fixture.schemaDirectory, fixture.config)

        val group = model.entities.single { it.graphqlName == "Group" }
        assertFalse(group.attributes.single { it.name == "name" }.nullable)
    }

    @Test
    fun `semantic non-null YAML reaches dynamic Hibernate mappings`() {
        val fixture = fixture()
        fixture.config.writeText("types:\n  Group:\n    fields:\n      name:\n        semanticNotNull: true\n")
        val output = fixture.schemaDirectory.parentFile.resolve("generated")

        HibernateSchemaModelWriter().write(
            model = PersistenceSchemaModelLoader.build(fixture.schemaDirectory, fixture.config),
            outputDirectory = output,
        )

        assertFalse(output.resolve("kotlin").exists())
        val mapping = output.resolve("resources/META-INF/viaduct-persistence.hbm.xml").readText()
        assertTrue(mapping.contains("<property name=\"name\" not-null=\"true\" type=\"string\">"))
        assertTrue(mapping.contains("<column name=\"name\" not-null=\"true\" sql-type=\"text\"/>"))
        assertEquals(
            "Group.name",
            output.resolve("resources/META-INF/viaduct-persistence-semantic-not-null.txt").readText().trim(),
        )
    }

    @Test
    fun `rejects ineligible denylist entries`() {
        val fixture = fixture()
        fixture.config.writeText("types:\n  Missing:\n    excluded: true\n")

        val failure =
            assertFailsWith<IllegalArgumentException> {
                PersistenceSchemaModelLoader.build(fixture.schemaDirectory, fixture.config)
            }

        assertTrue(failure.message!!.contains("Missing"))
    }

    @Test
    fun `rejects persisted relationships to denied types`() {
        val fixture = fixture(groupField = "audit: AuditEvent")
        fixture.config.writeText("types:\n  AuditEvent:\n    excluded: true\n")

        val failure =
            assertFailsWith<IllegalArgumentException> {
                PersistenceSchemaModelLoader.build(fixture.schemaDirectory, fixture.config)
            }

        assertTrue(failure.message!!.contains("Group.audit"))
        assertTrue(failure.message!!.contains("AuditEvent"))
    }

    private fun fixture(
        groupField: String = "name: String",
        auditField: String = "",
    ): Fixture {
        val root = Files.createTempDirectory("persistence-policy").toFile()
        val schemaDirectory =
            root.resolve("schema").apply {
                check(mkdirs() || isDirectory)
            }
        schemaDirectory.resolve("Model.graphqls").writeText(
            """
            directive @resolver on FIELD_DEFINITION
            interface Node { id: ID! }

            type Group implements Node {
              id: ID
              $groupField
            }

            type AuditEvent implements Node {
              id: ID
              $auditField
            }
            """.trimIndent(),
        )
        return Fixture(schemaDirectory, root.resolve("pg-persistence.yaml"))
    }

    private data class Fixture(
        val schemaDirectory: java.io.File,
        val config: java.io.File,
    )
}
