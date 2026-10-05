package dev.viaduct.persistence.hibernate

import dev.viaduct.persistence.model.PersistenceModelBuilder
import viaduct.graphql.schema.graphqljava.extensions.ViaductSchemaFactory
import kotlin.test.Test
import kotlin.test.assertEquals

class PersistenceAssociationMappingTest {
    @Test
    fun `concrete association retains its original columns and constraints`() {
        assertEquals(
            listOf(
                HbmBasicMapping(
                    "internalId",
                    "uuid",
                    "_viaduct_id",
                    false,
                    true,
                    columnDefinition = "uuid default gen_random_uuid()",
                ),
                HbmToOneMapping("owner", "Activity", "activityId", false, "FK_ActivityMembersAssociation_owner"),
                HbmToOneMapping("node", "Person", "personId", false, "FK_ActivityMembersAssociation_node"),
                HbmBasicMapping("label", "string", "label", true, false),
            ),
            mapping("ActivityMembersAssociation").attributes,
        )
    }

    @Test
    fun `abstract association retains its original columns and constraints`() {
        assertEquals(
            listOf(
                HbmBasicMapping(
                    "internalId",
                    "uuid",
                    "internalId",
                    false,
                    true,
                    columnDefinition = "uuid default gen_random_uuid()",
                ),
                HbmBasicMapping("id", "string", "id", false, false, false, false, "text"),
                HbmToOneMapping("owner", "Activity", "ownerId", false, "FK_ActivitySubjectsReference_owner"),
                HbmToOneMapping("nodeGroup", "Group", "nodeGroupId", true, "FK_ActivitySubjectsReference_nodeGroup"),
                HbmToOneMapping(
                    name = "nodePerson",
                    targetEntityName = "Person",
                    columnName = "nodePersonId",
                    nullable = true,
                    foreignKeyName = "FK_ActivitySubjectsReference_nodePerson",
                ),
                HbmBasicMapping("label", "string", "label", true, false),
            ),
            mapping("ActivitySubjectsReference").attributes,
        )
    }

    @Test
    fun `association table names are unchanged`() {
        assertEquals(
            listOf("ActivityMembersAssociation", "ActivitySubjectsReference"),
            listOf(mapping("ActivityMembersAssociation"), mapping("ActivitySubjectsReference")).map { it.tableName },
        )
    }

    @Test
    fun `self references retain separate owner and target columns`() {
        val schema =
            ViaductSchemaFactory.fromTypeDefinitionRegistry(
                """
                interface Node { id: ID! }
                type Person implements Node { id: ID! friends: PersonConnection }
                type PersonConnection { edges: [PersonEdge!]! }
                type PersonEdge { node: Person! label: String }
                """.trimIndent(),
            )
        val model = PersistenceModelBuilder().build(schema, setOf("Person"))
        val row = PersistenceModelToHbmMapper.map(model).entities.single { it.entityName == "PersonFriendsAssociation" }
        assertEquals(
            listOf(
                HbmToOneMapping("owner", "Person", "ownerPersonId", false, "FK_PersonFriendsAssociation_owner"),
                HbmToOneMapping("node", "Person", "targetPersonId", false, "FK_PersonFriendsAssociation_node"),
            ),
            row.attributes.filterIsInstance<HbmToOneMapping>(),
        )
    }

    private fun mapping(name: String): HbmEntityMapping {
        val schema =
            ViaductSchemaFactory.fromTypeDefinitionRegistry(
                """
                interface Node { id: ID! }
                type Activity implements Node { id: ID! members: PersonConnection subjects: SubjectConnection }
                type Person implements Node { id: ID! }
                type Group implements Node { id: ID! }
                union Subject = Person | Group
                type PersonConnection { edges: [PersonEdge!]! }
                type PersonEdge { node: Person! label: String }
                type SubjectConnection { edges: [SubjectEdge!]! }
                type SubjectEdge { node: Subject! label: String }
                """.trimIndent(),
            )
        val model = PersistenceModelBuilder().build(schema, setOf("Activity", "Person", "Group"))
        val restored = PersistenceModelYaml.fromYaml(PersistenceModelYaml.toYaml(model))
        return PersistenceModelToHbmMapper.map(restored).entities.single { it.entityName == name }
    }
}
