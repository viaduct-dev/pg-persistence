package dev.viaduct.persistence.model

import viaduct.graphql.schema.graphqljava.extensions.ViaductSchemaFactory
import kotlin.test.Test
import kotlin.test.assertEquals

class ResolverConnectionModelTest {
    @Test
    fun `paginated resolver connections retain their database relationship`() {
        val schema =
            ViaductSchemaFactory.fromTypeDefinitionRegistry(
                """
                directive @resolver(isSelective: Boolean, isBatching: Boolean) on FIELD_DEFINITION
                interface Node { id: ID! }
                type Group implements Node {
                  id: ID!
                  members(first: Int, after: String): MemberCollection!
                    @resolver(isSelective: true, isBatching: true)
                }
                type Member implements Node { id: ID! }
                type MemberCollection { edges: [MemberEdge!]! }
                type MemberEdge { node: Member!, cursor: String! }
                """.trimIndent(),
            )
        val types = setOf("Group", "Member")
        validatePgGraphqlDbs(schema, types)
        val model =
            PersistenceModelBuilder().build(
                schema,
                types,
                PersistenceModelPolicy(unidirectionalTargetForeignKeyFields = setOf("Group.members")),
            )
        val members =
            model.entities
                .single { it.graphqlName == "Group" }
                .attributes
                .filterIsInstance<PersistenceToManyAttribute>()
                .single()
        assertEquals(
            PersistenceToManyAttribute(
                name = "members",
                nullable = false,
                targetTypeName = "Member",
                inverseFieldName = null,
                storage = PersistenceToManyStorage.TARGET_FOREIGN_KEY,
            ),
            members,
        )
    }
}
