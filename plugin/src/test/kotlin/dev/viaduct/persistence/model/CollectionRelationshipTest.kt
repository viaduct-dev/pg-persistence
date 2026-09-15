package dev.viaduct.persistence.model

import dev.viaduct.persistence.hibernate.HibernateSchemaModelWriter
import viaduct.graphql.schema.graphqljava.extensions.ViaductSchemaFactory
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CollectionRelationshipTest {
    @Test
    fun `allows multiple unpaired references to the same target type`() {
        // pg_graphql naming disambiguation for this case is handled downstream by
        // PgGraphqlConstraintRenderer's synthesized local_name, not by the semantic model.
        val schema =
            ViaductSchemaFactory.fromTypeDefinitionRegistry(
                """
                type Group {
                  id: ID!
                  owner: User!
                  createdBy: User!
                }

                type User {
                  id: ID!
                }
                """.trimIndent(),
            )

        val model = PersistenceModelBuilder().build(schema, setOf("Group", "User"))
        assertEquals(2, model.entities.size)
    }

    @Test
    fun `allows multiple references to the same target type from different owning types`() {
        val schema =
            ViaductSchemaFactory.fromTypeDefinitionRegistry(
                """
                type Group {
                  id: ID!
                  owner: User!
                }

                type Team {
                  id: ID!
                  owner: User!
                }

                type User {
                  id: ID!
                }
                """.trimIndent(),
            )

        val model = PersistenceModelBuilder().build(schema, setOf("Group", "Team", "User"))
        assertEquals(3, model.entities.size)
    }

    @Test
    fun `rejects a declared reverse collection when the target has multiple unpaired matches`() {
        val schema = ambiguousReverseCollectionSchema()

        val failure =
            assertFailsWith<IllegalArgumentException> {
                PersistenceModelBuilder().build(schema, setOf("DiscordServerRoleGroup", "ExternalGroup"))
            }
        assertTrue(failure.message!!.contains("externalGroup"))
        assertTrue(failure.message!!.contains("server"))
        assertTrue(failure.message!!.contains("inverseFieldOverrides"))
        assertTrue(failure.message!!.contains("ExternalGroup.discordServerRoles"))
    }

    @Test
    fun `inverseFieldOverrides pairs a declared reverse collection with a specific to-one field`() {
        val schema = ambiguousReverseCollectionSchema()

        val model =
            PersistenceModelBuilder().build(
                schema,
                setOf("DiscordServerRoleGroup", "ExternalGroup"),
                policy =
                    PersistenceModelPolicy(
                        inverseFieldOverrides = mapOf("ExternalGroup.discordServerRoles" to "server"),
                    ),
            )

        val externalGroup = model.entities.single { it.graphqlName == "ExternalGroup" }
        val discordServerRoles =
            externalGroup.attributes.single { it.name == "discordServerRoles" } as PersistenceToManyAttribute
        assertEquals(PersistenceToManyStorage.TARGET_FOREIGN_KEY, discordServerRoles.storage)
        assertEquals("server", discordServerRoles.inverseFieldName)
    }

    @Test
    fun `inverseFieldOverrides naming a nonexistent field fails clearly`() {
        val schema = ambiguousReverseCollectionSchema()

        val failure =
            assertFailsWith<IllegalArgumentException> {
                PersistenceModelBuilder().build(
                    schema,
                    setOf("DiscordServerRoleGroup", "ExternalGroup"),
                    policy =
                        PersistenceModelPolicy(
                            inverseFieldOverrides = mapOf("ExternalGroup.discordServerRoles" to "nonexistent"),
                        ),
                )
            }
        assertTrue(failure.message!!.contains("nonexistent"))
    }

    private fun ambiguousReverseCollectionSchema() =
        ViaductSchemaFactory.fromTypeDefinitionRegistry(
            """
            type DiscordServerRoleGroup {
              id: ID!
              externalGroup: ExternalGroup!
              server: ExternalGroup!
            }

            type ExternalGroup {
              id: ID!
              discordServerRoles: [DiscordServerRoleGroup!]!
            }
            """.trimIndent(),
        )

    @Test
    fun `uses join tables for duplicate and mutual collection relationships`() {
        val schema =
            ViaductSchemaFactory.fromTypeDefinitionRegistry(
                """
                type Alpha {
                  id: ID!
                  primaryBetas: [Beta!]!
                  featuredBetas: [Beta!]!
                  gammas: [Gamma!]!
                }

                type Beta {
                  id: ID!
                }

                type Gamma {
                  id: ID!
                  alphas: [Alpha!]!
                }
                """.trimIndent(),
            )

        val model =
            PersistenceModelBuilder().build(
                schema,
                setOf("Alpha", "Beta", "Gamma"),
            )
        val alpha = model.entities.single { it.graphqlName == "Alpha" }
        val primary =
            alpha.attributes.single { it.name == "primaryBetas" }
                as PersistenceToManyAttribute
        val featured =
            alpha.attributes.single { it.name == "featuredBetas" }
                as PersistenceToManyAttribute
        assertEquals(PersistenceToManyStorage.JOIN_TABLE_OWNER, primary.storage)
        assertEquals(PersistenceToManyStorage.JOIN_TABLE_OWNER, featured.storage)
        assertNotEquals(primary.joinTableName, featured.joinTableName)

        val alphaGammas =
            alpha.attributes.single { it.name == "gammas" }
                as PersistenceToManyAttribute
        val gammaAlphas =
            model.entities
                .single { it.graphqlName == "Gamma" }
                .attributes
                .single { it.name == "alphas" } as PersistenceToManyAttribute
        assertEquals(
            setOf(
                PersistenceToManyStorage.JOIN_TABLE_OWNER,
                PersistenceToManyStorage.JOIN_TABLE_INVERSE,
            ),
            setOf(alphaGammas.storage, gammaAlphas.storage),
        )
        assertEquals(alphaGammas.joinTableName, gammaAlphas.joinTableName)
    }

    @Test
    fun `uses a target foreign key for a single unidirectional collection`() {
        val schema =
            ViaductSchemaFactory.fromTypeDefinitionRegistry(
                """
                type Group {
                  id: ID!
                  members: [Person!]!
                }

                type Person {
                  id: ID!
                }
                """.trimIndent(),
            )

        val model = PersistenceModelBuilder().build(schema, setOf("Group", "Person"))
        val members =
            model.entities
                .single { it.graphqlName == "Group" }
                .attributes
                .single { it.name == "members" } as PersistenceToManyAttribute

        assertEquals(PersistenceToManyStorage.TARGET_FOREIGN_KEY, members.storage)
        assertEquals(null, members.joinTableName)
    }

    @Test
    fun `treats a connection field as a collection relationship`() {
        val schema =
            ViaductSchemaFactory.fromTypeDefinitionRegistry(
                """
                directive @connection on OBJECT
                directive @edge on OBJECT

                type Group {
                  id: ID!
                  members: PersonConnection!
                }

                type Person {
                  id: ID!
                }

                type PersonConnection @connection {
                  edges: [PersonEdge!]!
                }

                type PersonEdge @edge {
                  node: Person!
                }
                """.trimIndent(),
            )

        val model = PersistenceModelBuilder().build(schema, setOf("Group", "Person"))
        val members =
            model.entities
                .single { it.graphqlName == "Group" }
                .attributes
                .single { it.name == "members" } as PersistenceToManyAttribute

        assertEquals("Person", members.targetTypeName)
        assertEquals(PersistenceToManyStorage.TARGET_FOREIGN_KEY, members.storage)
        assertEquals(null, members.inverseFieldName)
    }

    @Test
    fun `detects connections by shape rather than directives or type suffixes`() {
        val schema =
            ViaductSchemaFactory.fromTypeDefinitionRegistry(
                """
                type Group {
                  id: ID!
                  members: PersonPage!
                }

                type Person {
                  id: ID!
                }

                type PersonPage {
                  edges: [PersonLink!]!
                }

                type PersonLink {
                  node: Person!
                }
                """.trimIndent(),
            )

        val model = PersistenceModelBuilder().build(schema, setOf("Group", "Person"))
        val members =
            model.entities
                .single { it.graphqlName == "Group" }
                .attributes
                .single { it.name == "members" } as PersistenceToManyAttribute

        assertEquals("Person", members.targetTypeName)
        assertEquals(PersistenceToManyStorage.TARGET_FOREIGN_KEY, members.storage)
    }

    @Test
    fun `allows an existing unidirectional target foreign key by configuration`() {
        val schema =
            ViaductSchemaFactory.fromTypeDefinitionRegistry(
                """
                type Group {
                  id: ID!
                  members: [Person!]!
                }

                type Person {
                  id: ID!
                }
                """.trimIndent(),
            )

        val model =
            PersistenceModelBuilder().build(
                schema,
                setOf("Group", "Person"),
                policy =
                    PersistenceModelPolicy(
                        unidirectionalTargetForeignKeyFields = setOf("Group.members"),
                    ),
            )
        val members =
            model.entities
                .single { it.graphqlName == "Group" }
                .attributes
                .single { it.name == "members" } as PersistenceToManyAttribute

        assertEquals(PersistenceToManyStorage.TARGET_FOREIGN_KEY, members.storage)
        assertEquals(null, members.joinTableName)
    }

    @Test
    fun `uses a join table for a self-referential collection`() {
        val schema =
            ViaductSchemaFactory.fromTypeDefinitionRegistry(
                """
                type Person {
                  id: ID!
                  friends: [Person!]!
                }
                """.trimIndent(),
            )

        val model = PersistenceModelBuilder().build(schema, setOf("Person"))
        val friends =
            model.entities
                .single()
                .attributes
                .single { it.name == "friends" } as PersistenceToManyAttribute

        assertEquals(PersistenceToManyStorage.JOIN_TABLE_OWNER, friends.storage)
        assertEquals("PersonFriendsAssociation", friends.joinTableName)
    }

    @Test
    fun `maps custom connection edge fields onto an association`() {
        val schema =
            ViaductSchemaFactory.fromTypeDefinitionRegistry(
                """
                directive @connection on OBJECT
                directive @edge on OBJECT

                type Group {
                  id: ID!
                  members: PersonConnection!
                }

                type Person {
                  id: ID!
                }

                type PersonConnection @connection {
                  edges: [PersonEdge!]!
                }

                type PersonEdge @edge {
                  node: Person!
                  role: String!
                  invitedBy: Person
                }
                """.trimIndent(),
            )

        val model = PersistenceModelBuilder().build(schema, setOf("Group", "Person"))
        val members =
            model.entities
                .single { it.graphqlName == "Group" }
                .attributes
                .single { it.name == "members" } as PersistenceToManyAttribute

        assertEquals(PersistenceToManyStorage.JOIN_TABLE_OWNER, members.storage)
        assertEquals("GroupMembersAssociation", members.joinTableName)
        assertEquals("PersonEdge", members.edgeMapping?.typeName)
        assertEquals(
            listOf(
                PersistenceBasicAttribute(
                    name = "role",
                    nullable = false,
                    kotlinType = "String",
                ),
                PersistenceToOneAttribute(
                    name = "invitedBy",
                    nullable = true,
                    targetTypeName = "Person",
                ),
            ),
            members.edgeMapping?.attributes,
        )
        assertEquals(1, model.associations.size)
        assertEquals("groupId", model.associations.single().ownerColumnName)
        assertEquals("personId", model.associations.single().targetColumnName)
    }

    @Test
    fun `keeps association-backed edge fields in the in-memory model`() {
        val schema =
            ViaductSchemaFactory.fromTypeDefinitionRegistry(
                """
                directive @connection on OBJECT
                directive @edge on OBJECT
                type Group { id: ID!, members: PersonConnection! }
                type Person { id: ID! }
                type PersonConnection @connection { edges: [PersonEdge!]! }
                type PersonEdge @edge { node: Person!, role: String!, invitedBy: Person }
                """.trimIndent(),
            )
        val model = PersistenceModelBuilder().build(schema, setOf("Group", "Person"))
        val mapping =
            model.entities
                .single { it.graphqlName == "Group" }
                .attributes
                .single { it.name == "members" } as PersistenceToManyAttribute
        assertEquals("PersonEdge", mapping.edgeMapping?.typeName)
        assertEquals(listOf("role", "invitedBy"), mapping.edgeMapping?.attributes?.map { it.name })
    }

    @Test
    fun `writes association payload columns into the Hibernate mapping`() {
        val schema =
            ViaductSchemaFactory.fromTypeDefinitionRegistry(
                """
                directive @connection on OBJECT
                directive @edge on OBJECT
                type Group { id: ID!, members: PersonConnection! }
                type Person { id: ID! }
                type PersonConnection @connection { edges: [PersonEdge!]! }
                type PersonEdge @edge { node: Person!, role: String!, invitedBy: Person }
                """.trimIndent(),
            )
        val model = PersistenceModelBuilder().build(schema, setOf("Group", "Person"))
        val output = Files.createTempDirectory("persistence-edge-mapping").toFile()
        try {
            HibernateSchemaModelWriter().write(model, output, "generated")
            val mapping = output.resolve("resources/META-INF/viaduct-persistence.hbm.xml").readText()
            assertTrue(mapping.contains("entity-name=\"GroupMembersAssociation\""))
            assertTrue(mapping.contains("name=\"members\" table=\"GroupMembersAssociation\""))
            assertTrue(mapping.contains("<property name=\"role\""))
            assertTrue(mapping.contains("name=\"role\" not-null=\"true\""))
            assertTrue(mapping.contains("name=\"invitedBy\""))
            assertTrue(mapping.contains("name=\"invitedById\" not-null=\"false\""))
        } finally {
            output.deleteRecursively()
        }
    }
}
