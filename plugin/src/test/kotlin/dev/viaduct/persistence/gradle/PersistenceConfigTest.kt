package dev.viaduct.persistence.gradle

import dev.viaduct.persistence.model.PersistenceModelPolicy
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PersistenceConfigTest {
    @Test
    fun `returns empty defaults for absent and empty files`() {
        assertEquals(PersistenceModelPolicy(), PersistenceConfig.load(null))
        val missing = Files.createTempDirectory("persistence-config").resolve("missing.yaml").toFile()
        assertEquals(PersistenceModelPolicy(), PersistenceConfig.load(missing))
        val empty = Files.createTempFile("persistence-config", ".yaml").toFile()
        assertEquals(PersistenceModelPolicy(), PersistenceConfig.load(empty))
    }

    @Test
    fun `parses the complete persistence policy`() {
        val file =
            yaml(
                """
            types:
              AuditEvent:
                excluded: true
              Group:
                semanticNotNull: true
                fields:
                  members:
                    relationship:
                      storage: targetForeignKey
              Person:
                fields:
                  displayName:
                    semanticNotNull: true
              ExternalGroup:
                fields:
                  discordServerRoles:
                    relationship:
                      inverseField: server
            """,
            )

        val config = PersistenceConfig.load(file)

        assertEquals(setOf("AuditEvent"), config.deniedTypeNames)
        assertEquals(setOf("Group"), config.semanticNotNullTypeNames)
        assertEquals(setOf("Person.displayName"), config.semanticNotNullFieldCoordinates)
        assertEquals(setOf("Group.members"), config.unidirectionalTargetForeignKeyFields)
        assertEquals(mapOf("ExternalGroup.discordServerRoles" to "server"), config.inverseFieldOverrides)
    }

    @Test
    fun `parses single and composite unique keys alongside other policies`() {
        val file =
            yaml(
                """
                types:
                  Person:
                    semanticNotNull: true
                    unique: [[username], [region, externalId]]
                    fields:
                      displayName:
                        semanticNotNull: true
                """,
            )

        assertEquals(
            mapOf("Person" to listOf(listOf("username"), listOf("region", "externalId"))),
            PersistenceConfig.load(file).uniqueKeysByType,
        )
    }

    @Test
    fun `rejects unknown keys`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                PersistenceConfig.load(yaml("types:\n  Group:\n    allow: true"))
            }
        assertTrue(failure.message!!.contains("types.Group contains unknown key"))
    }

    @Test
    fun `rejects wrong value types and duplicates`() {
        assertFailsWith<IllegalArgumentException> {
            PersistenceConfig.load(yaml("types: Group"))
        }
        assertFailsWith<IllegalArgumentException> {
            PersistenceConfig.load(yaml("types:\n  Group:\n    excluded: \"true\""))
        }
    }

    @Test
    fun `rejects duplicate YAML mapping keys`() {
        assertFailsWith<IllegalArgumentException> {
            PersistenceConfig.load(
                yaml(
                    """
                    types:
                      Group: { excluded: true }
                      Group: { semanticNotNull: true }
                    """,
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PersistenceConfig.load(
                yaml(
                    """
                    types:
                      Group:
                        fields:
                          members: { semanticNotNull: true }
                          members: { relationship: { inverseField: owner } }
                    """,
                ),
            )
        }
    }

    @Test
    fun `rejects legacy policy shape with migration instructions`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                PersistenceConfig.load(yaml("denyList:\n  types: [Group]"))
            }
        assertTrue(failure.message!!.contains("legacy feature-first persistence policy"))
        assertTrue(failure.message!!.contains("type-first 'types' mapping"))
    }

    @Test
    fun `rejects legacy filename with migration instructions`() {
        val directory = Files.createTempDirectory("persistence-config").toFile()
        val file = directory.resolve("persistence.yaml").apply { writeText("types: {}") }

        val failure = assertFailsWith<IllegalArgumentException> { PersistenceConfig.load(file) }

        assertTrue(failure.message!!.contains("pg-persistence.yaml"))
    }

    @Test
    fun `rejects ineffective and contradictory policies`() {
        assertFailsWith<IllegalArgumentException> {
            PersistenceConfig.load(yaml("types:\n  Group: {}"))
        }
        assertFailsWith<IllegalArgumentException> {
            PersistenceConfig.load(
                yaml("types:\n  Group:\n    excluded: true\n    semanticNotNull: true"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PersistenceConfig.load(yaml("types:\n  Group:\n    fields:\n      name: {}"))
        }
        assertFailsWith<IllegalArgumentException> {
            PersistenceConfig.load(
                yaml(
                    "types:\n  Group:\n    fields:\n      members:\n" +
                        "        relationship:\n          storage: association",
                ),
            )
        }
    }

    private fun yaml(contents: String) =
        Files.createTempFile("persistence-config", ".yaml").toFile().apply {
            writeText(contents.trimIndent())
        }
}
