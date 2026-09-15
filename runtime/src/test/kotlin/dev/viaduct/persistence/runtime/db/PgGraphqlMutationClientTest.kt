package dev.viaduct.persistence.runtime.db

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import viaduct.api.types.Input
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PgGraphqlMutationClientTest {
    private val json = Json
    private val person = PgGraphqlEntity("Person")

    @Test
    fun `an explicitly converted Viaduct input can be inserted`() =
        runBlocking {
            var requestBody = ""
            val client =
                client { request ->
                    requestBody = request.bodyText()
                    """{"data":{"insertIntoPersonCollection":{"affectedCount":1}}}"""
                }

            val input = TestInput(mapOf("name" to "Ada", "active" to true)).toPgGraphqlInsert()
            client.insert(person, input)

            val objects =
                json
                    .parseToJsonElement(requestBody)
                    .jsonObject
                    .getValue("variables")
                    .jsonObject
                    .getValue("objects") as kotlinx.serialization.json.JsonArray
            assertEquals(
                "Ada",
                objects
                    .single()
                    .jsonObject
                    .getValue("name")
                    .jsonPrimitive.content,
            )
            assertEquals(
                "true",
                objects
                    .single()
                    .jsonObject
                    .getValue("active")
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `insert uses pg_graphql collection mutation variables and per-call headers`() =
        runBlocking {
            var requestBody = ""
            val client =
                client { request ->
                    assertEquals("Bearer access-token", request.headers[HttpHeaders.Authorization])
                    requestBody = request.bodyText()
                    """{"data":{"insertIntoPersonCollection":{"affectedCount":1,"records":[{"uuidId":"p1"}]}}}"""
                }

            val payload =
                client.insert(
                    entity = person,
                    objects = buildJsonArray { add(buildJsonObject { put("name", "Ada") }) },
                    selection = "affectedCount records { uuidId }",
                    headers = mapOf(HttpHeaders.Authorization to "Bearer access-token"),
                )

            val request = json.parseToJsonElement(requestBody).jsonObject
            assertEquals(
                "mutation Insert(\$objects: [PersonInsertInput!]!) { " +
                    "insertIntoPersonCollection(objects: \$objects) { affectedCount records { uuidId } } }",
                request["query"]?.jsonPrimitive?.content,
            )
            assertEquals(
                "Ada",
                request["variables"]
                    ?.jsonObject
                    ?.get("objects")
                    ?.let { it as kotlinx.serialization.json.JsonArray }
                    ?.single()
                    ?.jsonObject
                    ?.get("name")
                    ?.jsonPrimitive
                    ?.content,
            )
            assertEquals(1, payload["affectedCount"]?.jsonPrimitive?.content?.toInt())
        }

    @Test
    fun `update and delete use filters and explicit row limits`() =
        runBlocking {
            val requests = mutableListOf<String>()
            val client =
                client { request ->
                    requests += request.bodyText()
                    val key = if (requests.size == 1) "updatePersonCollection" else "deleteFromPersonCollection"
                    """{"data":{"$key":{"affectedCount":1}}}"""
                }
            val filter = buildJsonObject { put("uuidId", buildJsonObject { put("eq", "p1") }) }

            client.update(person, buildJsonObject { put("name", "Grace") }, filter, atMost = 1)
            client.delete(person, filter, atMost = 1)

            val update = json.parseToJsonElement(requests[0]).jsonObject
            assertEquals(
                "mutation Update(\$set: PersonUpdateInput!, \$filter: PersonFilter!, \$atMost: Int!) { " +
                    "updatePersonCollection(set: \$set, filter: \$filter, atMost: \$atMost) { affectedCount } }",
                update["query"]?.jsonPrimitive?.content,
            )
            assertEquals(
                "1",
                update["variables"]
                    ?.jsonObject
                    ?.get("atMost")
                    ?.jsonPrimitive
                    ?.content,
            )

            val delete = json.parseToJsonElement(requests[1]).jsonObject
            assertEquals(
                "mutation Delete(\$filter: PersonFilter!, \$atMost: Int!) { " +
                    "deleteFromPersonCollection(filter: \$filter, atMost: \$atMost) { affectedCount } }",
                delete["query"]?.jsonPrimitive?.content,
            )
        }

    @Test
    fun `result operations preserve pg_graphql errors and strict operations throw`() =
        runBlocking {
            val client =
                client {
                    """
                    {"data":{"insertIntoPersonCollection":{"affectedCount":0}},
                     "errors":[{"message":"duplicate","path":["insertIntoPersonCollection"],
                     "extensions":{"code":"23505"}}]}
                    """.trimIndent()
                }
            val objects = buildJsonArray { add(buildJsonObject { put("name", "Ada") }) }

            val result = client.insertResult(person, objects)

            assertEquals(
                0,
                result.data
                    ?.get("affectedCount")
                    ?.jsonPrimitive
                    ?.content
                    ?.toInt(),
            )
            assertEquals("duplicate", result.errors.single().message)
            assertEquals(
                "23505",
                result.errors
                    .single()
                    .extensions["code"]
                    ?.jsonPrimitive
                    ?.content,
            )
            assertFailsWith<UpstreamGraphqlException> { client.insert(person, objects) }
        }

    private fun client(response: (io.ktor.client.request.HttpRequestData) -> String): PgGraphqlMutationClient =
        PgGraphqlMutationClient(
            HttpClient(
                MockEngine { request ->
                    respond(
                        content = response(request),
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
            ),
            "https://example.test/graphql/v1",
        )

    private fun io.ktor.client.request.HttpRequestData.bodyText(): String =
        (body as OutgoingContent.ByteArrayContent).bytes().decodeToString()

    private class TestInput(
        val inputData: Map<String, Any?>,
    ) : Input
}
