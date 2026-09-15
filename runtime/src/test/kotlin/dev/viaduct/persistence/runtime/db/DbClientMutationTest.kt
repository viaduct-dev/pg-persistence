package dev.viaduct.persistence.runtime.db

import graphql.schema.GraphQLInputObjectType
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import viaduct.api.context.ExecutionContext
import viaduct.api.types.Input
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DbClientMutationTest {
    @Test
    fun `insert derives the entity from the generic and passes input fields and context headers`() =
        runBlocking {
            val requests = mutableListOf<JsonObject>()
            val client = client(requests)

            client.insertRaw(
                mockk<ExecutionContext>(),
                TestInput("AddGroupInput", mapOf("name" to "Chess", "description" to "Weekly games"))
                    .toPgGraphqlInsert(),
                "Group",
            )

            val request = requests.single()
            assertEquals(
                "mutation Insert(\$objects: [GroupInsertInput!]!) { " +
                    "insertIntoGroupCollection(objects: \$objects) { affectedCount records { uuidId } } }",
                request.getValue("query").jsonPrimitive.content,
            )
            val inserted =
                request
                    .getValue("variables")
                    .jsonObject
                    .getValue("objects")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("Chess", inserted.getValue("name").jsonPrimitive.content)
        }

    @Test
    fun `insert converts typed global ids to internal ids`() =
        runBlocking {
            val requests = mutableListOf<JsonObject>()
            val client = client(requests)
            val groupId = "00000000-0000-0000-0000-000000000001"
            val encodedGroupId =
                java.util.Base64
                    .getEncoder()
                    .encodeToString("Group:$groupId".toByteArray())

            client.insertRaw(
                mockk<ExecutionContext>(),
                TestInput("AddGroupMemberInput", mapOf("groupId" to encodedGroupId)).toPgGraphqlInsert(),
                "GroupMember",
            )

            val inserted =
                requests
                    .single()
                    .getValue("variables")
                    .jsonObject
                    .getValue("objects")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals(groupId, inserted.getValue("groupId").jsonPrimitive.content)
        }

    @Test
    fun `update uses the root typed id as its filter and excludes it from set`() =
        runBlocking {
            val requests = mutableListOf<JsonObject>()
            val client = client(requests)

            client.updateRaw(
                mockk<ExecutionContext>(),
                TestInput(
                    "UpdateGroupInput",
                    mapOf("groupId" to "group-1", "personId" to "person-1", "name" to "New name"),
                ).toPgGraphqlUpdate("Group"),
                "Group",
            )

            val variables = requests.single().getValue("variables").jsonObject
            assertEquals(
                "New name",
                variables
                    .getValue("set")
                    .jsonObject
                    .getValue("name")
                    .jsonPrimitive.content,
            )
            assertEquals(
                "person-1",
                variables
                    .getValue("set")
                    .jsonObject
                    .getValue("personId")
                    .jsonPrimitive.content,
            )
            assertEquals(false, variables.getValue("set").jsonObject.containsKey("groupId"))
            assertEquals(
                "group-1",
                variables
                    .getValue("filter")
                    .jsonObject
                    .getValue("uuidId")
                    .jsonObject
                    .getValue("eq")
                    .jsonPrimitive.content,
            )
            assertEquals("1", variables.getValue("atMost").jsonPrimitive.content)
        }

    @Test
    fun `delete requires and uses a root typed id`() =
        runBlocking {
            val requests = mutableListOf<JsonObject>()
            val client = client(requests)

            client.deleteRaw(
                mockk<ExecutionContext>(),
                TestInput("DeleteGroupInput", mapOf("groupId" to "group-1")).toPgGraphqlDelete("Group"),
                "Group",
            )

            val variables = requests.single().getValue("variables").jsonObject
            assertEquals(
                "group-1",
                variables
                    .getValue("filter")
                    .jsonObject
                    .getValue("uuidId")
                    .jsonObject
                    .getValue("eq")
                    .jsonPrimitive.content,
            )

            assertFailsWith<IllegalArgumentException> {
                TestInput("AddGroupInput", mapOf("name" to "No identifier")).toPgGraphqlDelete("Group")
            }
        }

    @Test
    fun `update requires an explicit identifier field when matching ids are ambiguous`() =
        runBlocking {
            val requests = mutableListOf<JsonObject>()
            val client = client(requests)
            val input =
                TestInput(
                    "AmbiguousUpdateGroupInput",
                    mapOf(
                        "groupId" to "group-1",
                        "parentGroupId" to "group-2",
                        "name" to "New name",
                    ),
                )

            val failure =
                assertFailsWith<IllegalArgumentException> {
                    input.toPgGraphqlUpdate("Group")
                }
            assertEquals(true, failure.message?.contains("Pass identifierField explicitly"))

            client.updateRaw(
                mockk<ExecutionContext>(),
                input.toPgGraphqlUpdate("Group", identifierField = "groupId"),
                "Group",
            )

            val variables = requests.single().getValue("variables").jsonObject
            assertEquals(
                "group-1",
                variables
                    .getValue("filter")
                    .jsonObject
                    .getValue("uuidId")
                    .jsonObject
                    .getValue("eq")
                    .jsonPrimitive.content,
            )
            assertEquals(false, variables.getValue("set").jsonObject.containsKey("groupId"))
            assertEquals(
                "group-2",
                variables
                    .getValue("set")
                    .jsonObject
                    .getValue("parentGroupId")
                    .jsonPrimitive.content,
            )
        }

    private fun client(requests: MutableList<JsonObject>): DbClient =
        DbClient(
            httpClient =
                HttpClient(
                    MockEngine { request ->
                        assertEquals("Bearer resolver-token", request.headers[HttpHeaders.Authorization])
                        requests += request.bodyJson()
                        val query =
                            requests
                                .last()
                                .getValue("query")
                                .jsonPrimitive.content
                        val responseKey =
                            when {
                                "insertIntoGroupMemberCollection" in query -> "insertIntoGroupMemberCollection"
                                "insertInto" in query -> "insertIntoGroupCollection"
                                "update" in query -> "updateGroupCollection"
                                else -> "deleteFromGroupCollection"
                            }
                        respond(
                            content = """{"data":{"$responseKey":{"affectedCount":1,"records":[]}}}""",
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ),
            endpoint = "https://example.test/graphql/v1",
            requestHeaders = DbRequestHeaders { mapOf(HttpHeaders.Authorization to "Bearer resolver-token") },
        )

    private fun io.ktor.client.request.HttpRequestData.bodyJson(): JsonObject =
        Json.parseToJsonElement((body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject

    private class TestInput(
        typeName: String,
        val inputData: Map<String, Any?>,
    ) : Input {
        val graphQLInputObjectType: GraphQLInputObjectType = graphQLInputType(typeName)
    }

    companion object {
        private fun graphQLInputType(typeName: String): GraphQLInputObjectType =
            SchemaGenerator()
                .makeExecutableSchema(
                    SchemaParser().parse(
                        """
                        directive @idOf(type: String!) on INPUT_FIELD_DEFINITION
                        input AddGroupInput { name: String!, description: String }
                        input AddGroupMemberInput { groupId: ID! @idOf(type: "Group") }
                        input UpdateGroupInput {
                          groupId: ID! @idOf(type: "Group")
                          personId: ID! @idOf(type: "Person")
                          name: String!
                        }
                        input DeleteGroupInput { groupId: ID! @idOf(type: "Group") }
                        input AmbiguousUpdateGroupInput {
                          groupId: ID! @idOf(type: "Group")
                          parentGroupId: ID! @idOf(type: "Group")
                          name: String!
                        }
                        type Query { value: String }
                        """.trimIndent(),
                    ),
                    RuntimeWiring.newRuntimeWiring().build(),
                ).let { schema -> requireNotNull(schema.getTypeAs<GraphQLInputObjectType>(typeName)) }
    }
}
