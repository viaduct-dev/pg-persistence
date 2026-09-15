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
            denyList:
              types: [AuditEvent]
            semanticNotNull:
              types: [Group]
              fields: [Person.displayName]
            relationships:
              unidirectionalTargetForeignKeyFields: [Group.members]
              inverseFieldOverrides:
                ExternalGroup.discordServerRoles: server
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
    fun `rejects unknown keys`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                PersistenceConfig.load(yaml("denyList:\n  allowTypes: [Group]"))
            }
        assertTrue(failure.message!!.contains("denyList contains unknown key"))
    }

    @Test
    fun `rejects wrong value types and duplicates`() {
        assertFailsWith<IllegalArgumentException> {
            PersistenceConfig.load(yaml("denyList:\n  types: Group"))
        }
        assertFailsWith<IllegalArgumentException> {
            PersistenceConfig.load(yaml("semanticNotNull:\n  fields: [Group.name, Group.name]"))
        }
    }

    @Test
    fun `rejects duplicate YAML mapping keys`() {
        assertFailsWith<IllegalArgumentException> {
            PersistenceConfig.load(
                yaml(
                    """
                    denyList:
                      types: [Group]
                    denyList:
                      types: [Person]
                    """,
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PersistenceConfig.load(
                yaml(
                    """
                    relationships:
                      inverseFieldOverrides:
                        Group.members: group
                        Group.members: owner
                    """,
                ),
            )
        }
    }

    private fun yaml(contents: String) =
        Files.createTempFile("persistence-config", ".yaml").toFile().apply {
            writeText(contents.trimIndent())
        }
}
