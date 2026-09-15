package dev.viaduct.persistence.model

import viaduct.graphql.schema.graphqljava.extensions.ViaductSchemaFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdOfForeignKeyTest {
    @Test
    fun `a scalar ID field with @idOf becomes a to-one relationship to its target type`() {
        val schema =
            ViaductSchemaFactory.fromTypeDefinitionRegistry(
                """
                directive @idOf(type: String!) on FIELD_DEFINITION

                type Group {
                  id: ID!
                }

                type Person {
                  id: ID!
                  groupId: ID @idOf(type: "Group")
                }
                """.trimIndent(),
            )

        val model = PersistenceModelBuilder().build(schema, setOf("Group", "Person"))

        val person = model.entities.single { it.graphqlName == "Person" }
        val groupId = person.attributes.single { it.name == "groupId" } as PersistenceToOneAttribute
        assertEquals("Group", groupId.targetTypeName)
        assertTrue(groupId.idOfDirected)
    }

    @Test
    fun `an @idOf field directs the foreign key for an unpaired to-many collection`() {
        val schema =
            ViaductSchemaFactory.fromTypeDefinitionRegistry(
                """
                directive @idOf(type: String!) on FIELD_DEFINITION

                type Group {
                  id: ID!
                  members: [Person!]!
                }

                type Person {
                  id: ID!
                  groupId: ID @idOf(type: "Group")
                }
                """.trimIndent(),
            )

        val model = PersistenceModelBuilder().build(schema, setOf("Group", "Person"))

        val group = model.entities.single { it.graphqlName == "Group" }
        val members = group.attributes.single { it.name == "members" } as PersistenceToManyAttribute
        assertEquals(PersistenceToManyStorage.TARGET_FOREIGN_KEY, members.storage)
        assertEquals("groupId", members.inverseFieldName)
    }

    @Test
    fun `a plain ID field without @idOf is not treated as a relationship`() {
        val schema =
            ViaductSchemaFactory.fromTypeDefinitionRegistry(
                """
                type Group {
                  id: ID!
                }

                type Person {
                  id: ID!
                  externalRef: ID
                }
                """.trimIndent(),
            )

        val model = PersistenceModelBuilder().build(schema, setOf("Group", "Person"))

        val person = model.entities.single { it.graphqlName == "Person" }
        assertFalse(person.attributes.single { it.name == "externalRef" } is PersistenceToOneAttribute)
    }

    @Test
    fun `an @idOf field and matching object field share one relationship`() {
        val schema =
            ViaductSchemaFactory.fromTypeDefinitionRegistry(
                """
                directive @idOf(type: String!) on FIELD_DEFINITION

                type Group {
                  id: ID!
                }

                type Person {
                  id: ID!
                  group: Group!
                  groupId: ID @idOf(type: "Group")
                }
                """.trimIndent(),
            )

        val model = PersistenceModelBuilder().build(schema, setOf("Group", "Person"))

        val person = model.entities.single { it.graphqlName == "Person" }
        val group = person.attributes.single { it.name == "group" } as PersistenceToOneAttribute
        assertEquals("Group", group.targetTypeName)
        assertFalse(group.idOfDirected)
        assertFalse(group.nullable)
        assertFalse(person.attributes.any { it.name == "groupId" })
    }

    @Test
    fun `an @idOf field targeting another type still conflicts with an object field`() {
        val schema =
            ViaductSchemaFactory.fromTypeDefinitionRegistry(
                """
                directive @idOf(type: String!) on FIELD_DEFINITION

                type Group { id: ID! }
                type Team { id: ID! }

                type Person {
                  id: ID!
                  group: Group!
                  groupId: ID @idOf(type: "Team")
                }
                """.trimIndent(),
            )

        val failure =
            assertFailsWith<IllegalArgumentException> {
                PersistenceModelBuilder().build(schema, setOf("Group", "Team", "Person"))
            }
        assertTrue(failure.message!!.contains("group/groupId"))
    }

    @Test
    fun `multiple @idOf fields may target the same type without conflict`() {
        val schema =
            ViaductSchemaFactory.fromTypeDefinitionRegistry(
                """
                directive @idOf(type: String!) on FIELD_DEFINITION

                type User {
                  id: ID!
                }

                type Group {
                  id: ID!
                  ownerId: ID @idOf(type: "User")
                  createdById: ID @idOf(type: "User")
                }
                """.trimIndent(),
            )

        val model = PersistenceModelBuilder().build(schema, setOf("Group", "User"))

        val group = model.entities.single { it.graphqlName == "Group" }
        val owner = group.attributes.single { it.name == "ownerId" } as PersistenceToOneAttribute
        val createdBy = group.attributes.single { it.name == "createdById" } as PersistenceToOneAttribute
        assertEquals("User", owner.targetTypeName)
        assertEquals("User", createdBy.targetTypeName)
    }
}
