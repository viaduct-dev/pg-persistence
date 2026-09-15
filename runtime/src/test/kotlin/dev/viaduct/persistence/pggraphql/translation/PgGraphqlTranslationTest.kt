package dev.viaduct.persistence.pggraphql.translation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class PgGraphqlTranslationTest {
    private val schema =
        PgGraphqlTranslationSchema(
            collectionElementTypes =
                mapOf(
                    "GroupCollection" to "Group",
                    "GroupMemberCollection" to "GroupMember",
                ),
            fieldTypes =
                mapOf(
                    PgGraphqlFieldCoordinate("Group", "members") to "GroupMemberCollection",
                ),
        )

    @Test
    fun `rewrites collection fragments and flat nodes`() {
        val translated =
            PgGraphqlTranslation.translateSelectionDocument(
                "fragment Main on GroupCollection { nodes { id name } }",
                schema,
            )

        assertContains(translated, "GroupConnection")
        assertContains(translated, "_viaduct_nodes:edges{node{id name}}")
    }

    @Test
    fun `restores flat node response shape`() {
        val response =
            Json.parseToJsonElement(
                """{"_viaduct_nodes":[{"node":{"id":"1"}},{"node":{"id":"2"}}]}""",
            )

        assertEquals(
            """{"nodes":[{"id":"1"},{"id":"2"}]}""",
            PgGraphqlTranslation.restoreViaductResponseShape(response).toString(),
        )
    }

    @Test
    fun `restores nested node response shapes recursively`() {
        val response =
            Json.parseToJsonElement(
                """
                {
                  "_viaduct_nodes": [
                    {
                      "node": {
                        "id": "1",
                        "members": {
                          "_viaduct_nodes": [{"node": {"id": "2"}}]
                        }
                      }
                    }
                  ],
                  "pageInfo": {"hasNextPage": false}
                }
                """.trimIndent(),
            )

        assertEquals(
            Json.parseToJsonElement(
                """{"nodes":[{"id":"1","members":{"nodes":[{"id":"2"}]}}],"pageInfo":{"hasNextPage":false}}""",
            ),
            PgGraphqlTranslation.restoreViaductResponseShape(response),
        )
    }

    @Test
    fun `rewrites nested collections using schema types`() {
        val translated =
            PgGraphqlTranslation.translateSelectionDocument(
                """
                fragment Main on GroupCollection {
                  nodes { members { nodes { id } } }
                }
                """.trimIndent(),
                schema,
            )

        assertContains(
            translated,
            "_viaduct_nodes:edges{node{members{_viaduct_nodes:edges{node{id}}}}}",
        )
    }

    @Test
    fun `rewrites collection type conditions in inline fragments`() {
        val translated =
            PgGraphqlTranslation.translateSelectionDocument(
                """
                fragment Main on GroupCollection {
                  ... on GroupCollection { nodes { id } }
                }
                """.trimIndent(),
                schema,
            )

        assertContains(translated, "fragment Main on GroupConnection")
        assertContains(
            translated,
            "...on GroupConnection{_viaduct_nodes:edges{node{id}}}",
        )
    }

    @Test
    fun `preserves ordinary nodes and edges fields`() {
        val domainSchema =
            PgGraphqlTranslationSchema(
                collectionElementTypes = emptyMap(),
                fieldTypes = emptyMap(),
            )
        val translated =
            PgGraphqlTranslation.translateSelectionDocument(
                "fragment Main on Graph { nodes { id } edges { cursor } }",
                domainSchema,
            )
        val response =
            Json.parseToJsonElement(
                """{"nodes":[{"id":"1"}],"edges":[{"cursor":"c"}]}""",
            )

        assertContains(translated, "nodes{id}")
        assertContains(translated, "edges{cursor}")
        assertEquals(
            response,
            PgGraphqlTranslation.restoreViaductResponseShape(response),
        )
    }

    @Test
    fun `builds a filtered collection query for a single db result`() {
        val query =
            PgGraphqlTranslation.buildRootQuery(
                field = "groupCollection",
                arguments = "(filter: {uuidId: {eq: \$id}})",
                variableDefinitions = "\$id: UUID!",
                fragmentDocument = "fragment Main on Group { id name }",
                singleViaFilteredCollection = true,
            )

        assertContains(query, "query ViaductDb(\$id: UUID!)")
        assertContains(
            query,
            "groupCollection(filter: {uuidId: {eq: \$id}}) " +
                "{ edges { node { ...Main } } }",
        )
        assertContains(query, "fragment Main on Group { id name }")
    }

    @Test
    fun `passes standard connections through without connection metadata`() {
        val translated =
            PgGraphqlTranslation.translateSelectionDocument(
                """
                fragment Main on Group {
                  members(first: 2, after: "cursor") {
                    edges { cursor node { id name } }
                    pageInfo { hasNextPage endCursor }
                  }
                }
                """.trimIndent(),
                PgGraphqlTranslationSchema(
                    collectionElementTypes = emptyMap(),
                    fieldTypes = emptyMap(),
                ),
            )

        assertContains(
            translated,
            "members(first:2,after:\"cursor\"){edges{cursor node{id name}}" +
                "pageInfo{hasNextPage endCursor}}",
        )
        assertFalse(translated.contains("_viaduct_nodes"))
    }

    @Test
    fun `keeps a single unidirectional connection on its direct relationship`() {
        val translated =
            PgGraphqlTranslation.translateSelectionDocument(
                "fragment Main on Group { members { edges { cursor node { id } } } }",
                PgGraphqlTranslationSchema(
                    collectionElementTypes = emptyMap(),
                    fieldTypes =
                        mapOf(
                            PgGraphqlFieldCoordinate("Group", "members") to "PersonConnection",
                            PgGraphqlFieldCoordinate("PersonConnection", "edges") to "PersonEdge",
                            PgGraphqlFieldCoordinate("PersonEdge", "node") to "Person",
                        ),
                ),
            )

        assertContains(translated, "members{edges{cursor node{id}}}")
        assertFalse(translated.contains("membersAssociations"))
    }

    @Test
    fun `rewrites association-backed connections to real relationships`() {
        val translated =
            PgGraphqlTranslation.translateSelectionDocument(
                """
                fragment Main on Group {
                  members(first: 2) {
                    nodes { id }
                    edges { cursor node { id } role }
                    pageInfo { hasNextPage }
                  }
                }
                """.trimIndent(),
                associationSchema(),
            )

        assertContains(
            translated,
            "_viaduct_association_connection_members:membersAssociations(first:2)",
        )
        assertContains(translated, "_viaduct_association_nodes_nodes:edges")
        assertContains(translated, "_viaduct_association_edges_edges:edges")
        assertContains(translated, "node{_viaduct_association_node_node:node{id}role}")
    }

    @Test
    fun `rewrites plain mutual connections to real relationships`() {
        val translated =
            PgGraphqlTranslation.translateSelectionDocument(
                "fragment Main on Group { members { edges { cursor node { id } } } }",
                plainAssociationSchema(),
            )

        assertContains(translated, "_viaduct_association_connection_members:membersAssociations")
        assertContains(translated, "_viaduct_association_edges_edges:edges")
        assertContains(translated, "node{_viaduct_association_node_node:node{id}}")
    }

    @Test
    fun `restores association rows and nested node selections`() {
        assertEquals(
            expectedAssociationResponse(),
            PgGraphqlTranslation.restoreViaductResponseShape(associationResponse()),
        )
    }

    @Test
    fun `restores error paths with the response shape`() {
        val path =
            PgGraphqlTranslation.restoreViaductResponsePath(
                listOf(
                    JsonPrimitive("_viaduct_association_connection_members"),
                    JsonPrimitive("_viaduct_association_edges_edges"),
                    JsonPrimitive(0),
                    JsonPrimitive("node"),
                    JsonPrimitive("_viaduct_association_node_node"),
                    JsonPrimitive("id"),
                ),
            )

        assertEquals(
            listOf("members", "edges", "0", "node", "id"),
            path.map { it.toString().trim('"') },
        )
    }

    @Test
    fun `restores an error path when null bubbling removed its data subtree`() {
        val path =
            PgGraphqlTranslation.restoreViaductResponsePath(
                listOf(
                    JsonPrimitive("_viaduct_association_connection_members"),
                    JsonPrimitive("_viaduct_association_edges_edges"),
                    JsonPrimitive(0),
                    JsonPrimitive("node"),
                    JsonPrimitive("_viaduct_association_node_node"),
                    JsonPrimitive("id"),
                ),
            )

        assertEquals(
            listOf("members", "edges", "0", "node", "id"),
            path.map { it.toString().trim('"') },
        )
    }

    @Test
    fun `preserves aliases while translating association connections`() {
        val translated =
            PgGraphqlTranslation.translateSelectionDocument(
                "fragment Main on Group { people: members { links: edges { c: cursor target: node { id } r: role } } }",
                associationSchema(),
            )

        assertContains(translated, "_viaduct_association_connection_people:membersAssociations")
        assertContains(translated, "_viaduct_association_edges_links:edges")
        assertContains(translated, "_viaduct_association_node_target:node{id}")
    }

    @Test
    fun `keeps object-valued edge payload selections on the association row`() {
        val schema =
            associationSchema(
                fieldTypes =
                    associationSchema().fieldTypes +
                        (PgGraphqlFieldCoordinate("PersonEdge", "invitedBy") to "Person"),
            )
        val translated =
            PgGraphqlTranslation.translateSelectionDocument(
                "fragment Main on Group { members { edges { node { id } invitedBy { id } } } }",
                schema,
            )

        assertContains(translated, "node{_viaduct_association_node_node:node{id}invitedBy{id}}")
    }

    @Test
    fun `rewrites nested association-backed connections recursively`() {
        val schema =
            associationSchema(
                fieldTypes =
                    mapOf(
                        PgGraphqlFieldCoordinate("Group", "members") to "PersonConnection",
                        PgGraphqlFieldCoordinate("PersonConnection", "edges") to "PersonEdge",
                        PgGraphqlFieldCoordinate("PersonEdge", "node") to "Person",
                        PgGraphqlFieldCoordinate("PersonEdge", "role") to "Role",
                        PgGraphqlFieldCoordinate("Person", "groups") to "GroupConnection",
                        PgGraphqlFieldCoordinate("GroupConnection", "edges") to "GroupEdge",
                        PgGraphqlFieldCoordinate("GroupEdge", "node") to "Group",
                        PgGraphqlFieldCoordinate("GroupEdge", "label") to "String",
                    ),
            )

        val translated =
            PgGraphqlTranslation.translateSelectionDocument(
                "fragment Main on Group { members { edges { node { groups { edges { node { id } label } } } role } } }",
                schema,
            )

        assertContains(translated, "membersAssociations")
        assertContains(translated, "groupsAssociations")
        assertContains(translated, "_viaduct_association_node_node")
    }

    @Test
    fun `splits named edge fragments between cursor metadata and stored fields`() {
        val translated =
            PgGraphqlTranslation.translateSelectionDocument(
                """
                fragment Main on Group { members { edges { ...PersonEdgeFields } } }
                fragment PersonEdgeFields on PersonEdge { cursor node { id } role }
                """.trimIndent(),
                associationSchema(),
            )

        assertContains(
            translated,
            "edges{...{cursor}_viaduct_association_row:node{...{_viaduct_association_node_node:node{id}role}}}",
        )
    }

    private fun associationSchema(
        fieldTypes: Map<PgGraphqlFieldCoordinate, String> =
            mapOf(
                PgGraphqlFieldCoordinate("Group", "members") to "PersonConnection",
                PgGraphqlFieldCoordinate("PersonConnection", "edges") to "PersonEdge",
                PgGraphqlFieldCoordinate("PersonConnection", "pageInfo") to "PageInfo",
                PgGraphqlFieldCoordinate("PersonEdge", "node") to "Person",
                PgGraphqlFieldCoordinate("PersonEdge", "role") to "String",
            ),
    ) = PgGraphqlTranslationSchema(
        collectionElementTypes = emptyMap(),
        fieldTypes = fieldTypes,
    )

    private fun plainAssociationSchema() =
        PgGraphqlTranslationSchema(
            collectionElementTypes = emptyMap(),
            fieldTypes =
                mapOf(
                    PgGraphqlFieldCoordinate("Group", "members") to "PersonConnection",
                    PgGraphqlFieldCoordinate("PersonConnection", "edges") to "PersonEdge",
                    PgGraphqlFieldCoordinate("PersonEdge", "node") to "Person",
                    PgGraphqlFieldCoordinate("Person", "groups") to "GroupConnection",
                    PgGraphqlFieldCoordinate("GroupConnection", "edges") to "GroupEdge",
                    PgGraphqlFieldCoordinate("GroupEdge", "node") to "Group",
                ),
            associationConnections =
                setOf(
                    PgGraphqlFieldCoordinate("Group", "members"),
                ),
        )

    private fun associationResponse() =
        Json.parseToJsonElement(
            """
            {
              "_viaduct_association_connection_members": {
                "_viaduct_association_nodes_nodes": [
                  {"node": {"_viaduct_association_node_nodes": {"id": "1"}}}
                ],
                "_viaduct_association_edges_edges": [
                  {
                    "cursor": "cursor-1",
                    "node": {
                      "_viaduct_association_node_node": {
                        "id": "1",
                        "_viaduct_association_connection_groups": {
                          "_viaduct_association_edges_edges": [
                            {
                              "node": {
                                "_viaduct_association_node_node": {"id": "2"},
                                "label": "child"
                              }
                            }
                          ]
                        }
                      },
                      "role": "admin"
                    }
                  }
                ],
                "pageInfo": {"hasNextPage": false}
              }
            }
            """.trimIndent(),
        )

    private fun expectedAssociationResponse() =
        Json.parseToJsonElement(
            """
            {
              "members": {
                "nodes": [{"id": "1"}],
                "edges": [
                  {
                    "cursor": "cursor-1",
                    "node": {
                      "id": "1",
                      "groups": {
                        "edges": [{"node": {"id": "2"}, "label": "child"}]
                      }
                    },
                    "role": "admin"
                  }
                ],
                "pageInfo": {"hasNextPage": false}
              }
            }
            """.trimIndent(),
        )

    @Test
    fun `rewrites nodes selected on a standard connection`() {
        val connectionSchema =
            PgGraphqlTranslationSchema(
                collectionElementTypes = emptyMap(),
                fieldTypes =
                    mapOf(
                        PgGraphqlFieldCoordinate("Group", "members") to "PersonConnection",
                        PgGraphqlFieldCoordinate("PersonConnection", "edges") to "PersonEdge",
                        PgGraphqlFieldCoordinate("PersonEdge", "node") to "Person",
                    ),
            )
        val translated =
            PgGraphqlTranslation.translateSelectionDocument(
                "fragment Main on Group { members { nodes { id } } }",
                connectionSchema,
            )

        assertContains(translated, "members{_viaduct_nodes:edges{node{id}}}")
    }

    @Test
    fun `rewrites nodes for arbitrary structural connection type names`() {
        val schema =
            PgGraphqlTranslationSchema(
                collectionElementTypes = emptyMap(),
                fieldTypes =
                    mapOf(
                        PgGraphqlFieldCoordinate("Group", "members") to "MembershipPage",
                        PgGraphqlFieldCoordinate("MembershipPage", "edges") to "MembershipLink",
                        PgGraphqlFieldCoordinate("MembershipLink", "node") to "Person",
                    ),
            )

        val translated =
            PgGraphqlTranslation.translateSelectionDocument(
                "fragment Main on Group { members { nodes { id } } }",
                schema,
            )

        assertContains(translated, "members{_viaduct_nodes:edges{node{id}}}")
    }

    @Test
    fun `rewrites nested nodes on structural connections`() {
        val schema =
            PgGraphqlTranslationSchema(
                collectionElementTypes = emptyMap(),
                fieldTypes =
                    mapOf(
                        PgGraphqlFieldCoordinate("Group", "members") to "MembershipPage",
                        PgGraphqlFieldCoordinate("MembershipPage", "edges") to "MembershipLink",
                        PgGraphqlFieldCoordinate("MembershipLink", "node") to "Membership",
                        PgGraphqlFieldCoordinate("Membership", "groups") to "GroupPage",
                        PgGraphqlFieldCoordinate("GroupPage", "edges") to "GroupLink",
                        PgGraphqlFieldCoordinate("GroupLink", "node") to "Group",
                    ),
            )

        val translated =
            PgGraphqlTranslation.translateSelectionDocument(
                "fragment Main on Group { members { nodes { groups { nodes { id } } } } }",
                schema,
            )

        assertContains(
            translated,
            "members{_viaduct_nodes:edges{node{groups{_viaduct_nodes:edges{node{id}}}}}}",
        )
    }

    @Test
    fun `rejects the internal response alias in authored selections`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                PgGraphqlTranslation.translateSelectionDocument(
                    "fragment Main on Group { _viaduct_nodes: name }",
                    schema,
                )
            }

        assertContains(error.message.orEmpty(), "reserved alias")
    }
}
