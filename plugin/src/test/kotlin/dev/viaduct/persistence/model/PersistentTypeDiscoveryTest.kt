package dev.viaduct.persistence.model

import viaduct.graphql.schema.graphqljava.extensions.ViaductSchemaFactory
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class PersistentTypeDiscoveryTest {
    @Test
    fun `includes Node-implementing types and excludes id-bearing types that don't implement Node`() {
        val sdl =
            """
            interface Node {
              id: ID!
            }

            type Group implements Node {
              id: ID!
              name: String!
            }

            type ExternalRef {
              id: ID!
            }
            """.trimIndent()

        val schemaFile = Files.createTempFile("Model", ".graphqls").toFile().apply { writeText(sdl) }
        val schema = ViaductSchemaFactory.fromTypeDefinitionRegistry(sdl)

        val discovered = discoverPersistentTypeNames(listOf(schemaFile), schema)

        assertEquals(setOf("Group"), discovered)
    }

    @Test
    fun `discovers Node-implementing types across schema files`() {
        val ordinarySdl =
            """
            interface Node {
              id: ID!
            }

            type Group implements Node {
              id: ID!
            }
            """.trimIndent()
        val externalSdl =
            """
            type ExternalThing implements Node {
              id: ID!
            }
            """.trimIndent()

        val ordinaryFile = Files.createTempFile("Model", ".graphqls").toFile().apply { writeText(ordinarySdl) }
        val externalFile = Files.createTempFile("External", ".graphqls").toFile().apply { writeText(externalSdl) }
        val schema = ViaductSchemaFactory.fromTypeDefinitionRegistry("$ordinarySdl\n$externalSdl")

        val discovered = discoverPersistentTypeNames(listOf(ordinaryFile, externalFile), schema)

        assertEquals(setOf("Group", "ExternalThing"), discovered)
    }

    @Test
    fun `includes types that implement Node transitively through another interface`() {
        val sdl =
            """
            interface Node {
              id: ID!
            }

            interface Ownable implements Node {
              id: ID!
            }

            type Group implements Ownable {
              id: ID!
            }
            """.trimIndent()

        val schemaFile = Files.createTempFile("Model", ".graphqls").toFile().apply { writeText(sdl) }
        val schema = ViaductSchemaFactory.fromTypeDefinitionRegistry(sdl)

        val discovered = discoverPersistentTypeNames(listOf(schemaFile), schema)

        assertEquals(setOf("Group"), discovered)
    }
}
