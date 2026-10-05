package dev.viaduct.persistence.runtime.db
import dev.viaduct.persistence.runtime.connection.ConnectionFieldValueContext
import dev.viaduct.persistence.runtime.connection.ConnectionPageRequest
import dev.viaduct.persistence.runtime.connection.ConnectionPath
import dev.viaduct.persistence.runtime.connection.ConnectionShape
import dev.viaduct.persistence.runtime.connection.EdgeShape
import dev.viaduct.persistence.runtime.connection.NestedConnectionPageRequest
import dev.viaduct.persistence.runtime.connection.NodeResponseField
import dev.viaduct.persistence.runtime.connection.NodesResponseField
import dev.viaduct.persistence.runtime.connection.UuidConnectionEdge
import dev.viaduct.persistence.runtime.connection.customEdgeResponseField
import dev.viaduct.persistence.runtime.node.NodeReferenceKind
import dev.viaduct.persistence.runtime.node.NodeReferencePlanner
import dev.viaduct.persistence.runtime.node.NodeReferenceResolver
import dev.viaduct.persistence.runtime.node.NodeReferenceSelection
import dev.viaduct.persistence.runtime.reflection.AbstractRelationship
import dev.viaduct.persistence.runtime.reflection.GeneratedTypeReflection
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import viaduct.api.context.ExecutionContext
import viaduct.api.context.ResolverExecutionContext
import viaduct.api.reflect.CompositeField
import viaduct.api.reflect.Field
import viaduct.api.reflect.Type
import viaduct.api.select.FieldCoordinate
import viaduct.api.select.OutputSelectionFragment
import viaduct.api.select.SelectionSet
import viaduct.api.types.CompositeOutput
import viaduct.api.types.GRT
import viaduct.api.types.NodeObject
import viaduct.api.types.Query
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DbClientTest {
    @Test
    fun `fetches UUIDs and applies request headers`() =
        runBlocking {
            val engine =
                MockEngine { request ->
                    assertEquals("Bearer token", request.headers[HttpHeaders.Authorization])
                    assertEquals("anon-key", request.headers["apikey"])
                    respond(
                        content =
                            """{"data":{"groupCollection":{"edges":[""" +
                                """{"node":{"uuidId":"first"}},{"node":{"uuidId":"second"}}]}}}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client =
                DbClient(
                    httpClient = HttpClient(engine),
                    endpoint = "https://example.test/graphql/v1",
                    requestHeaders =
                        DbRequestHeaders {
                            mapOf(
                                HttpHeaders.Authorization to "Bearer token",
                                "apikey" to "anon-key",
                            )
                        },
                )

            assertEquals(
                listOf("first", "second"),
                client.fetchUuidIds(mockk<ExecutionContext>(), "groupCollection"),
            )
        }

    @Test
    fun `fetches UUID connection edges with provider cursors and page info`() =
        runBlocking {
            val engine =
                MockEngine {
                    respond(
                        content =
                            """
                            {
                              "data": {
                                "groupCollection": {
                                  "edges": [
                                    {"cursor":"cursor-1","node":{"uuidId":"first"}},
                                    {"cursor":"cursor-2","node":{"uuidId":"second"}}
                                  ],
                                  "pageInfo": {
                                    "hasNextPage": true,
                                    "hasPreviousPage": true,
                                    "startCursor": "cursor-1",
                                    "endCursor": "cursor-2"
                                  }
                                }
                              }
                            }
                            """.trimIndent(),
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client =
                DbClient(
                    httpClient = HttpClient(engine),
                    endpoint = "https://example.test/graphql/v1",
                )

            val page =
                client.fetchUuidConnection(
                    ctx = mockk<ExecutionContext>(),
                    collectionField = "groupCollection",
                    first = 2,
                    after = "cursor-0",
                )

            assertEquals(listOf("first", "second"), page.edges.map(UuidConnectionEdge::uuidId))
            assertEquals(listOf("cursor-1", "cursor-2"), page.edges.map(UuidConnectionEdge::cursor))
            assertEquals(true, page.pageInfo.hasNextPage)
            assertEquals("cursor-2", page.pageInfo.endCursor)
        }

    @Test
    fun `fetches backward UUID connection arguments`() =
        runBlocking {
            val requests = mutableListOf<JsonObject>()
            val client = backwardConnectionClient(requests)

            client.fetchUuidConnection(
                ctx = mockk<ExecutionContext>(),
                collectionField = "groupCollection",
                last = 1,
                before = "cursor-3",
            )

            val request = requests.single()
            assertEquals(
                "1",
                request["variables"]
                    ?.jsonObject
                    ?.get("last")
                    ?.jsonPrimitive
                    ?.content,
            )
            assertEquals(
                "cursor-3",
                request["variables"]
                    ?.jsonObject
                    ?.get("before")
                    ?.jsonPrimitive
                    ?.content,
            )
            assertContains(
                request["query"]?.jsonPrimitive?.content.orEmpty(),
                "last: ${'$'}last",
            )
            assertContains(
                request["query"]?.jsonPrimitive?.content.orEmpty(),
                "before: ${'$'}before",
            )
        }

    private fun backwardConnectionClient(requests: MutableList<JsonObject>): DbClient =
        DbClient(
            httpClient =
                HttpClient(
                    MockEngine { request ->
                        requests += requestJson(request.body)
                        respond(
                            content = backwardConnectionResponse(),
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ),
            endpoint = "https://example.test/graphql/v1",
        )

    private fun requestJson(body: OutgoingContent): JsonObject =
        Json
            .parseToJsonElement(
                (body as OutgoingContent.ByteArrayContent).bytes().decodeToString(),
            ).jsonObject

    private fun backwardConnectionResponse(): String =
        """
        {
          "data": {
            "groupCollection": {
              "edges": [{"cursor":"cursor-2","node":{"uuidId":"second"}}],
              "pageInfo": {
                "hasNextPage": true,
                "hasPreviousPage": true,
                "startCursor": "cursor-2",
                "endCursor": "cursor-2"
              }
            }
          }
        }
        """.trimIndent()

    @Test
    fun `fetches nested connections for all parents in one request`() =
        runBlocking {
            val requests = mutableListOf<String>()
            val client = nestedConnectionClient(requests)

            val pages =
                client.fetchNestedUuidConnections(
                    ctx = mockk<ExecutionContext>(),
                    parentCollectionField = "groupCollection",
                    parentIds = listOf("group-1", "group-2"),
                    childCollectionField = "members",
                    first = 1,
                )

            assertEquals(1, requests.size)
            assertContains(requests.single(), "groupCollection(filter: {uuidId: {in: \$parentIds}})")
            assertContains(requests.single(), "members(first: \$first")
            assertEquals(listOf("member-1"), pages.getValue("group-1").edges.map { it.uuidId })
            assertEquals(true, pages.getValue("group-1").pageInfo.hasNextPage)
            assertEquals(listOf("member-2"), pages.getValue("group-2").edges.map { it.uuidId })
            assertEquals(false, pages.getValue("group-2").pageInfo.hasNextPage)
        }

    @Test
    fun `forwards nested connection filters ordering and variables`() =
        runBlocking {
            val requests = mutableListOf<String>()
            val client = nestedConnectionClient(requests)
            val request =
                NestedConnectionPageRequest(
                    parentCollectionField = "groupCollection",
                    parentIds = listOf("group-1"),
                    child =
                        ConnectionPageRequest(
                            collectionField = "members",
                            first = 2,
                            additionalArguments =
                                "filter: {status: {eq: \$status}}, orderBy: [CREATED_AT_ASC]",
                            additionalVariableDefinitions = "\$status: String!",
                            additionalVariables =
                                Json.parseToJsonElement("""{"status":"ACTIVE"}""").jsonObject,
                        ),
                )

            client.fetchNestedUuidConnections(mockk<ExecutionContext>(), request)

            assertEquals(1, requests.size)
            assertContains(requests.single(), "\$status: String!")
            assertContains(
                requests.single(),
                "members(first: \$first, after: \$after, last: \$last, before: \$before, " +
                    "filter: {status: {eq: \$status}}, orderBy: [CREATED_AT_ASC])",
            )
            assertContains(requests.single(), "\"status\":\"ACTIVE\"")
        }

    private fun nestedConnectionClient(requests: MutableList<String>): DbClient =
        DbClient(
            httpClient =
                HttpClient(
                    MockEngine { request ->
                        requests += (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                        respond(
                            content = nestedConnectionResponse(),
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ),
            endpoint = "https://example.test/graphql/v1",
        )

    private fun nestedConnectionResponse(): String =
        """
        {
          "data": {
            "groupCollection": {
              "edges": [
                {
                  "node": {
                    "uuidId": "group-1",
                    "members": {
                      "edges": [{"cursor":"m-1","node":{"uuidId":"member-1"}}],
                      "pageInfo": {
                        "hasNextPage": true,
                        "hasPreviousPage": false,
                        "startCursor": "m-1",
                        "endCursor": "m-1"
                      }
                    }
                  }
                },
                {
                  "node": {
                    "uuidId": "group-2",
                    "members": {
                      "edges": [{"cursor":"m-2","node":{"uuidId":"member-2"}}],
                      "pageInfo": {
                        "hasNextPage": false,
                        "hasPreviousPage": true,
                        "startCursor": "m-2",
                        "endCursor": "m-2"
                      }
                    }
                  }
                }
              ]
            }
          }
        }
        """.trimIndent()

    @Test
    fun `surfaces upstream GraphQL errors`() =
        runBlocking {
            val engine =
                MockEngine {
                    respond(
                        content = """{"errors":[{"message":"database unavailable"}]}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client =
                DbClient(
                    httpClient = HttpClient(engine),
                    endpoint = "https://example.test/graphql/v1",
                )

            val error =
                assertFailsWith<IllegalStateException> {
                    client.fetchUuidIds(mockk<ExecutionContext>(), "groupCollection")
                }
            check(error.message.orEmpty().contains("database unavailable"))
        }

    @Test
    fun `recognizes generated connection types by their edges node shape`() {
        val shape = GeneratedTypeReflection().connection(FixtureTypes.connection)

        assertEquals(FixtureNode::class, shape?.nodeField?.type?.kcls)
        assertEquals("cursor", shape?.cursorField?.name)
        assertEquals(
            listOf("hasNextPage", "hasPreviousPage", "startCursor", "endCursor"),
            shape
                ?.pageInfo
                ?.fields
                ?.map { it.field.name }
                ?.take(4),
        )
    }

    @Test
    fun `does not classify an ordinary nodes field as a collection`() {
        val schema = GeneratedTypeReflection().translationSchema(FixtureTypes.domain)

        assertEquals(null, schema.collectionElementType("FixtureDomain"))
        assertEquals("FixtureNode", schema.fieldType("FixtureDomain", "nodes"))
    }

    @Test
    fun `derives association-backed connections from reflected edge fields`() {
        val schema = GeneratedTypeReflection().translationSchema(FixtureTypes.node)

        assertEquals(true, schema.isAssociationConnection("FixtureNode", "members"))
    }

    @Test
    fun `derives association-backed storage for a plain mutual connection`() {
        val reflection = GeneratedTypeReflection()
        val schema = reflection.translationSchema(FixtureTypes.plainGroup)
        val shape =
            reflection.connection(
                FixtureTypes.plainPersonConnection,
                ownerType = FixtureTypes.plainGroup,
            )

        assertEquals(true, schema.isAssociationConnection("FixturePlainGroup", "members"))
        assertEquals(true, shape?.edge?.isAssociationBacked)
        assertEquals("membersAssociations", shape?.path("members")?.requestFieldName)
    }

    @Test
    fun `keeps a single unidirectional connection on the target relationship`() {
        val shape =
            GeneratedTypeReflection().connection(
                FixtureTypes.directConnection,
                ownerType = FixtureTypes.directOwner,
            )

        assertEquals(false, shape?.edge?.isAssociationBacked)
        assertEquals("members", shape?.path("members")?.requestFieldName)
    }

    @Test
    fun `translates association-backed connections in ordinary db queries`() {
        val selections = mockk<SelectionSet<FixtureNode>>()
        every { selections.type } returns FixtureTypes.node
        every { selections.toFragment() } returns
            OutputSelectionFragment(
                "Main",
                "fragment Main on FixtureNode { members { edges { cursor node { uuidId } weight } } }",
                emptyMap(),
            )

        val query =
            DbQueryPlanner(GeneratedTypeReflection()).plan(
                root = DbRoot("group"),
                selections = selections,
            )

        assertContains(query.text, "membersAssociations")
        assertContains(query.text, "_viaduct_association_node_node:node{uuidId}")
        assertContains(query.text, "weight")
    }

    @Test
    fun `translates plain mutual connections in ordinary db queries`() {
        val selections = mockk<SelectionSet<FixturePlainGroup>>()
        every { selections.type } returns FixtureTypes.plainGroup
        every { selections.toFragment() } returns
            OutputSelectionFragment(
                "Main",
                "fragment Main on FixturePlainGroup { members { edges { cursor node { uuidId } } } }",
                emptyMap(),
            )

        val query =
            DbQueryPlanner(GeneratedTypeReflection()).plan(
                root = DbRoot("group"),
                selections = selections,
            )

        assertContains(query.text, "membersAssociations")
        assertContains(query.text, "_viaduct_association_node_node:node{uuidId}")
    }

    @Test
    fun `does not inspect scalar types for generated fields`() {
        assertEquals(null, GeneratedTypeReflection().connection(FixtureTypes.cursor))
    }

    @Test
    fun `uses pg_graphql edges for connection node references`() {
        val connection = checkNotNull(GeneratedTypeReflection().connection(FixtureTypes.connection))
        val selection =
            NodeReferenceSelection(
                fieldName = "members",
                targetType = FixtureTypes.connection,
                kind = NodeReferenceKind.CONNECTION,
                nodeType = FixtureTypes.node,
                connection = connection,
            )

        assertContains(selection.upstreamSelection, "membersAssociations {")
        assertContains(selection.upstreamSelection, "edges { cursor node { node { uuidId }")
        assertContains(selection.upstreamSelection, "owner { uuidId }")
        assertContains(selection.upstreamSelection, "node { owner { uuidId } }")
        assertContains(selection.upstreamSelection, "node { weight }")
        assertContains(
            selection.upstreamSelection,
            "pageInfo { hasNextPage hasPreviousPage startCursor endCursor state totalCount }",
        )
        assertEquals(listOf("edges", "pageInfo"), connection.fields.map { it.field.name })
        assertContains(connection.edge.fields.map { it.field.name }, "owner")
        assertContains(connection.edge.fields.map { it.field.name }, "weight")
    }

    @Test
    fun `reads restored direct nodes when building a connection reference`() {
        val context = mockk<ResolverExecutionContext<out Query>>(relaxed = true)
        val nodeResolver = mockk<NodeReferenceResolver>()
        val node = mockk<FixtureNode>()
        every {
            nodeResolver.resolve(
                context,
                FixtureTypes.node,
                Json.parseToJsonElement("""{"uuidId":"member"}""").jsonObject,
            )
        } returns
            node
        val responseField =
            NodesResponseField(
                field = FixtureCompositeField("nodes", FixtureTypes.connection, FixtureTypes.node),
                edgeField = FixtureConnection.Fields.edges,
                edge = EdgeShape(FixtureTypes.edge, NodeResponseField(FixtureEdge.Fields.node), null),
            )
        val valueContext =
            ConnectionFieldValueContext(
                executionContext = context,
                typeReflection = GeneratedTypeReflection(),
                nodeResolver = nodeResolver,
                connectionFieldName = "members",
                path = ConnectionPath("members"),
            )

        val response = Json.parseToJsonElement("""{"nodes":[{"uuidId":"member"}]}""").jsonObject

        assertEquals(listOf(node), responseField.value(response, valueContext))
    }

    @Test
    fun `does not request subselections for enum-valued fields`() {
        val requestedSelections = mockk<SelectionSet<FixtureNode>>()
        every { requestedSelections.contains<FixtureNode>(any()) } returns true
        every { requestedSelections.toFragment() } returns
            OutputSelectionFragment(
                "Main",
                "fragment Main on FixtureNode { members status }",
                emptyMap(),
            )
        val ownedSelections = mockk<SelectionSet<FixtureNode>>()
        every { ownedSelections.type } returns FixtureTypes.node
        stubConnectionSelections(requestedSelections)

        val references =
            NodeReferencePlanner(
                typeReflection = GeneratedTypeReflection(),
            ).plan(requestedSelections, ownedSelections)

        assertEquals(listOf("members"), references.map(NodeReferenceSelection::fieldName))
    }

    @Test
    fun `forwards all Viaduct connection pagination arguments to node references`() {
        val requestedSelections = mockk<SelectionSet<FixtureNode>>()
        every { requestedSelections.toFragment() } returns
            OutputSelectionFragment(
                "Main",
                """
                fragment Main on FixtureNode {
                  members(first: ${'$'}first, after: ${'$'}after, last: ${'$'}last, before: ${'$'}before)
                }
                """.trimIndent(),
                mapOf(
                    "first" to 2,
                    "after" to "after-cursor",
                    "last" to 3,
                    "before" to "before-cursor",
                ),
            )
        every { requestedSelections.contains<FixtureNode>(any()) } returns true
        val ownedSelections = mockk<SelectionSet<FixtureNode>>()
        every { ownedSelections.type } returns FixtureTypes.node
        stubConnectionSelections(requestedSelections)

        val reference =
            NodeReferencePlanner(
                typeReflection = GeneratedTypeReflection(),
            ).plan(requestedSelections, ownedSelections).single()

        assertContains(
            reference.upstreamSelection,
            "membersAssociations(first:2,after:\"after-cursor\",last:3,before:\"before-cursor\")",
        )
        assertContains(reference.upstreamSelection, "edges { cursor node { node { uuidId }")
        assertContains(reference.upstreamSelection, "pageInfo { hasNextPage hasPreviousPage")
    }

    @Test
    fun `preserves schema-specific connection arguments and input values`() {
        val requestedSelections = mockk<SelectionSet<FixtureNode>>()
        every { requestedSelections.toFragment() } returns
            OutputSelectionFragment(
                "Main",
                """
                fragment Main on FixtureNode {
                  members(
                    first: ${'$'}first,
                    filter: {
                      status: ${'$'}status,
                      enabled: ${'$'}enabled,
                      tags: ${'$'}tags,
                      criteria: ${'$'}criteria
                    },
                    orderBy: ${'$'}orderBy
                  )
                }
                """.trimIndent(),
                mapOf(
                    "first" to 2,
                    "status" to FixtureStatus.ACTIVE,
                    "enabled" to true,
                    "tags" to listOf("important", "recent"),
                    "criteria" to linkedMapOf("source" to "imported"),
                    "orderBy" to FixtureSort.NAME,
                ),
            )
        every { requestedSelections.contains<FixtureNode>(any()) } returns true
        val ownedSelections = mockk<SelectionSet<FixtureNode>>()
        every { ownedSelections.type } returns FixtureTypes.node
        stubConnectionSelections(requestedSelections)

        val reference =
            NodeReferencePlanner(
                typeReflection = GeneratedTypeReflection(),
            ).plan(requestedSelections, ownedSelections).single()

        assertContains(reference.upstreamSelection, "first:2")
        assertContains(
            reference.upstreamSelection,
            "filter:{status:ACTIVE,enabled:true,tags:[\"important\",\"recent\"],criteria:{source:\"imported\"}}",
        )
        assertContains(reference.upstreamSelection, "orderBy:NAME")
    }

    private fun stubConnectionSelections(requestedSelections: SelectionSet<FixtureNode>) {
        val connectionSelections = mockk<SelectionSet<FixtureConnection>>(relaxed = true)
        val edgeSelections = mockk<SelectionSet<FixtureEdge>>(relaxed = true)
        val pageInfoSelections = mockk<SelectionSet<FixturePageInfo>>(relaxed = true)
        every {
            requestedSelections.selectionSetFor(FixtureNode.Fields.members)
        } returns connectionSelections
        every {
            connectionSelections.selectionSetFor(FixtureConnection.Fields.edges)
        } returns edgeSelections
        every {
            connectionSelections.selectionSetFor(FixtureConnection.Fields.pageInfo)
        } returns pageInfoSelections
        every { connectionSelections.selectedFieldCoordinates() } returns
            setOf(
                FieldCoordinate("FixtureConnection", "edges"),
                FieldCoordinate("FixtureConnection", "pageInfo"),
            )
        every { edgeSelections.selectedFieldCoordinates() } returns
            setOf(
                FieldCoordinate("FixtureEdge", "cursor"),
                FieldCoordinate("FixtureEdge", "node"),
                FieldCoordinate("FixtureEdge", "owner"),
                FieldCoordinate("FixtureEdge", "weight"),
            )
        every { pageInfoSelections.selectedFieldCoordinates() } returns
            setOf(
                FieldCoordinate("FixturePageInfo", "hasNextPage"),
                FieldCoordinate("FixturePageInfo", "hasPreviousPage"),
                FieldCoordinate("FixturePageInfo", "startCursor"),
                FieldCoordinate("FixturePageInfo", "endCursor"),
                FieldCoordinate("FixturePageInfo", "totalCount"),
            )
    }
}

class StoredEdgeResponseFieldTest {
    @Test
    fun `supports non-node composite edge associations`() {
        val selections = mockk<SelectionSet<FixtureAssociation>>()
        every { selections.type } returns FixtureTypes.association
        every { selections.toFragment() } returns
            OutputSelectionFragment(
                "Association",
                "fragment Association on FixtureAssociation { label }",
                emptyMap(),
            )
        val field =
            FixtureCompositeField<FixtureEdge, FixtureAssociation>(
                "association",
                FixtureTypes.edge,
                FixtureTypes.association,
            )

        val responseField = customEdgeResponseField(field, selections)

        assertContains(responseField.selection(), "node { association { label } }")
    }

    @Test
    fun `scalar edge field exposes its selection without a node wrapper`() {
        assertEquals("weight", customEdgeResponseField(FixtureEdge.Fields.weight, null).valueSelection())
    }

    @Test
    fun `node edge field exposes its selection without a node wrapper`() {
        assertEquals("owner { uuidId }", customEdgeResponseField(FixtureEdge.Fields.owner, null).valueSelection())
    }

    @Test
    fun `abstract connection selects custom fields directly on the public edge`() {
        val selections = mockk<SelectionSet<FixtureAssociation>>()
        every { selections.type } returns FixtureTypes.association
        every { selections.toFragment() } returns
            OutputSelectionFragment(
                "Association",
                "fragment Association on FixtureAssociation { label }",
                emptyMap(),
            )
        val field =
            FixtureCompositeField<FixtureEdge, FixtureAssociation>(
                "association",
                FixtureTypes.edge,
                FixtureTypes.association,
            )
        val connection = mockk<ConnectionShape>()
        every { connection.edge.customFields } returns listOf(customEdgeResponseField(field, selections))
        val reference =
            NodeReferenceSelection(
                fieldName = "subjects",
                targetType = FixtureTypes.connection,
                kind = NodeReferenceKind.ABSTRACT,
                nodeType = AbstractSubject.Reflection,
                connection = connection,
                abstractRelationship =
                    AbstractRelationship(
                        "Activity",
                        "subjects",
                        "AbstractSubject",
                        setOf("AbstractPerson"),
                        false,
                        collection = true,
                        connectionType = "SubjectConnection",
                        edgeType = "SubjectEdge",
                    ),
            )
        assertContains(
            reference.upstreamSelection(GeneratedTypeReflection()),
            "edges { cursor node { __typename ... on AbstractPerson { uuidId } } association { label } }",
        )
    }

    @Test
    fun `translates nested connections on composite edge fields`() {
        val selections = mockk<SelectionSet<FixtureAssociation>>()
        every { selections.type } returns FixtureTypes.association
        every { selections.toFragment() } returns
            OutputSelectionFragment(
                "Association",
                "fragment Association on FixtureAssociation { related { nodes { uuidId } } }",
                emptyMap(),
            )
        val field =
            FixtureCompositeField<FixtureEdge, FixtureAssociation>(
                "association",
                FixtureTypes.edge,
                FixtureTypes.association,
            )

        val responseField = customEdgeResponseField(field, selections)

        assertContains(
            responseField.selection(ConnectionPath("membersAssociations", "node"), GeneratedTypeReflection()),
            "relatedAssociations",
        )
    }
}

/** Minimal generated-type-shaped fixtures for exercising DbClient reflection. */
class FixtureDirectOwner : NodeObject {
    object Fields {
        val members: CompositeField<FixtureDirectOwner, FixtureDirectConnection> =
            FixtureCompositeField("members", FixtureTypes.directOwner, FixtureTypes.directConnection)
    }
}

class FixtureDirectPerson : NodeObject {
    object Fields
}

class FixtureDirectConnection : CompositeOutput {
    object Fields {
        val edges: CompositeField<FixtureDirectConnection, FixtureDirectEdge> =
            FixtureCompositeField("edges", FixtureTypes.directConnection, FixtureTypes.directEdge)
    }
}

class FixtureDirectEdge : CompositeOutput {
    object Fields {
        val node: CompositeField<FixtureDirectEdge, FixtureDirectPerson> =
            FixtureCompositeField("node", FixtureTypes.directEdge, FixtureTypes.directPerson)
        val cursor: Field<FixtureDirectEdge> = FixtureField("cursor", FixtureTypes.directEdge)
    }
}

class FixturePlainGroup : NodeObject {
    object Fields {
        val members: CompositeField<FixturePlainGroup, FixturePlainPersonConnection> =
            FixtureCompositeField("members", FixtureTypes.plainGroup, FixtureTypes.plainPersonConnection)
    }
}

class FixturePlainPerson : NodeObject {
    object Fields {
        val groups: CompositeField<FixturePlainPerson, FixturePlainGroupConnection> =
            FixtureCompositeField("groups", FixtureTypes.plainPerson, FixtureTypes.plainGroupConnection)
    }
}

class FixturePlainPersonConnection : CompositeOutput {
    object Fields {
        val edges: CompositeField<FixturePlainPersonConnection, FixturePlainPersonEdge> =
            FixtureCompositeField(
                "edges",
                FixtureTypes.plainPersonConnection,
                FixtureTypes.plainPersonEdge,
            )
    }
}

class FixturePlainGroupConnection : CompositeOutput {
    object Fields {
        val edges: CompositeField<FixturePlainGroupConnection, FixturePlainGroupEdge> =
            FixtureCompositeField(
                "edges",
                FixtureTypes.plainGroupConnection,
                FixtureTypes.plainGroupEdge,
            )
    }
}

class FixturePlainPersonEdge : CompositeOutput {
    object Fields {
        val node: CompositeField<FixturePlainPersonEdge, FixturePlainPerson> =
            FixtureCompositeField("node", FixtureTypes.plainPersonEdge, FixtureTypes.plainPerson)
        val cursor: Field<FixturePlainPersonEdge> =
            FixtureField("cursor", FixtureTypes.plainPersonEdge)
    }
}

class FixturePlainGroupEdge : CompositeOutput {
    object Fields {
        val node: CompositeField<FixturePlainGroupEdge, FixturePlainGroup> =
            FixtureCompositeField("node", FixtureTypes.plainGroupEdge, FixtureTypes.plainGroup)
        val cursor: Field<FixturePlainGroupEdge> =
            FixtureField("cursor", FixtureTypes.plainGroupEdge)
    }
}

class FixtureConnection : CompositeOutput {
    object Fields {
        val edges: CompositeField<FixtureConnection, FixtureEdge> =
            FixtureCompositeField("edges", FixtureTypes.connection, FixtureTypes.edge)
        val pageInfo: CompositeField<FixtureConnection, FixturePageInfo> =
            FixtureCompositeField("pageInfo", FixtureTypes.connection, FixtureTypes.pageInfo)
    }
}

class FixtureEdge : CompositeOutput {
    object Fields {
        val node: CompositeField<FixtureEdge, FixtureNode> =
            FixtureCompositeField("node", FixtureTypes.edge, FixtureTypes.node)
        val cursor: Field<FixtureEdge> = FixtureField("cursor", FixtureTypes.edge)
        val owner: CompositeField<FixtureEdge, FixtureNode> =
            FixtureCompositeField("owner", FixtureTypes.edge, FixtureTypes.node)
        val weight: Field<FixtureEdge> = FixtureField("weight", FixtureTypes.edge)
        val state: CompositeField<FixtureEdge, FixtureStatus> =
            FixtureCompositeField("state", FixtureTypes.edge, FixtureTypes.status)
    }
}

class FixturePageInfo : CompositeOutput {
    object Fields {
        val hasNextPage: Field<FixturePageInfo> =
            FixtureField("hasNextPage", FixtureTypes.pageInfo)
        val hasPreviousPage: Field<FixturePageInfo> =
            FixtureField("hasPreviousPage", FixtureTypes.pageInfo)
        val startCursor: Field<FixturePageInfo> =
            FixtureField("startCursor", FixtureTypes.pageInfo)
        val endCursor: Field<FixturePageInfo> =
            FixtureField("endCursor", FixtureTypes.pageInfo)
        val totalCount: Field<FixturePageInfo> =
            FixtureField("totalCount", FixtureTypes.pageInfo)
        val state: CompositeField<FixturePageInfo, FixtureStatus> =
            FixtureCompositeField("state", FixtureTypes.pageInfo, FixtureTypes.status)
    }
}

class FixtureNode : NodeObject {
    object Fields {
        val members: CompositeField<FixtureNode, FixtureConnection> =
            FixtureCompositeField("members", FixtureTypes.node, FixtureTypes.connection)
        val status: CompositeField<FixtureNode, FixtureStatus> =
            FixtureCompositeField("status", FixtureTypes.node, FixtureTypes.status)
    }
}

class FixtureDomain : CompositeOutput {
    object Fields {
        val nodes: CompositeField<FixtureDomain, FixtureNode> =
            FixtureCompositeField("nodes", FixtureTypes.domain, FixtureTypes.node)
    }
}

class FixtureAssociation : CompositeOutput {
    object Fields {
        val label: Field<FixtureAssociation> = FixtureField("label", FixtureTypes.association)
        val related: CompositeField<FixtureAssociation, FixtureConnection> =
            FixtureCompositeField("related", FixtureTypes.association, FixtureTypes.connection)
    }
}

class FixtureCursor : GRT

class FixtureBoolean : GRT

class FixtureInt : GRT

enum class FixtureStatus : viaduct.api.types.Enum {
    ACTIVE,
}

private enum class FixtureSort {
    NAME,
}

private object FixtureTypes {
    val directOwner = FixtureType("FixtureDirectOwner", FixtureDirectOwner::class)
    val directPerson = FixtureType("FixtureDirectPerson", FixtureDirectPerson::class)
    val directConnection = FixtureType("FixtureDirectConnection", FixtureDirectConnection::class)
    val directEdge = FixtureType("FixtureDirectEdge", FixtureDirectEdge::class)
    val plainGroup = FixtureType("FixturePlainGroup", FixturePlainGroup::class)
    val plainPerson = FixtureType("FixturePlainPerson", FixturePlainPerson::class)
    val plainPersonConnection =
        FixtureType("FixturePlainPersonConnection", FixturePlainPersonConnection::class)
    val plainGroupConnection =
        FixtureType("FixturePlainGroupConnection", FixturePlainGroupConnection::class)
    val plainPersonEdge = FixtureType("FixturePlainPersonEdge", FixturePlainPersonEdge::class)
    val plainGroupEdge = FixtureType("FixturePlainGroupEdge", FixturePlainGroupEdge::class)
    val connection = FixtureType("FixtureConnection", FixtureConnection::class)
    val edge = FixtureType("FixtureEdge", FixtureEdge::class)
    val node = FixtureType("FixtureNode", FixtureNode::class)
    val pageInfo = FixtureType("FixturePageInfo", FixturePageInfo::class)
    val status = FixtureType("FixtureStatus", FixtureStatus::class)
    val domain = FixtureType("FixtureDomain", FixtureDomain::class)
    val association = FixtureType("FixtureAssociation", FixtureAssociation::class)
    val cursor = FixtureType("FixtureCursor", FixtureCursor::class)
    val boolean = FixtureType("FixtureBoolean", FixtureBoolean::class)
    val int = FixtureType("FixtureInt", FixtureInt::class)
}

private class FixtureType<T : GRT>(
    override val name: String,
    override val kcls: KClass<out T>,
) : Type<T>

private class FixtureCompositeField<P : GRT, T : GRT>(
    override val name: String,
    override val containingType: Type<P>,
    override val type: Type<T>,
) : CompositeField<P, T>

private class FixtureField<P : GRT>(
    override val name: String,
    override val containingType: Type<P>,
) : Field<P>
