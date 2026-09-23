package dev.viaduct.persistence.runtime.db

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class PgGraphqlClientTest {
    @Test
    fun `SQL JSON results are decoded separately from the GraphQL envelope`() =
        runBlocking {
            val client = client { """{"data":{"names":"[\"Ada\",\"Grace\"]"}}""" }
            val result =
                client.executeJson(
                    document = "query { names }",
                    responseKey = "names",
                    deserializer = ListSerializer(String.serializer()),
                )
            assertEquals(listOf("Ada", "Grace"), result)
        }

    @Test
    fun `typed operation variables are encoded by the library`() =
        runBlocking {
            var requestBody = ""
            val client =
                client { request ->
                    requestBody = request.bodyText()
                    """{"data":{"allowed":true}}"""
                }
            client.execute(
                "query Allowed(\$name: String!) { allowed(name: \$name) }",
                PgGraphqlObject.of("name" to "Ada"),
                "allowed",
            )
            assertEquals(
                "Ada",
                Json
                    .parseToJsonElement(requestBody)
                    .jsonObject
                    .getValue("variables")
                    .jsonObject
                    .getValue("name")
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `execute supports application owned scalar operations and headers`() =
        runBlocking {
            val client =
                client { request ->
                    assertEquals("Bearer token", request.headers[HttpHeaders.Authorization])
                    """{"data":{"allowed":true}}"""
                }

            val result =
                client.execute(
                    document = "query Allowed { allowed }",
                    responseKey = "allowed",
                    headers = mapOf(HttpHeaders.Authorization to "Bearer token"),
                )

            assertEquals("true", result.jsonPrimitive.content)
        }

    @Test
    fun `select constructs collection query and returns nodes`() =
        runBlocking {
            var requestBody = ""
            val client =
                client { request ->
                    requestBody = request.bodyText()
                    """{"data":{"personCollection":{"edges":[{"node":{"uuidId":"p1"}}]}}}"""
                }

            val records =
                client.select(
                    entity = PgGraphqlEntity("Person"),
                    selection = "uuidId",
                    filter = buildJsonObject { put("active", buildJsonObject { put("eq", true) }) },
                    first = 2,
                    orderBy = buildJsonArray { add(buildJsonObject { put("createdAt", "DescNullsLast") }) },
                )

            assertEquals(
                "p1",
                records
                    .single()
                    .jsonObject
                    .getValue("uuidId")
                    .jsonPrimitive.content,
            )
            val request = Json.parseToJsonElement(requestBody).jsonObject
            assertEquals(
                "query Select(\$filter: PersonFilter, \$first: Int, \$orderBy: [PersonOrderBy!]) { " +
                    "personCollection(filter: \$filter, first: \$first, orderBy: \$orderBy) { " +
                    "edges { node { uuidId } } } }",
                request.getValue("query").jsonPrimitive.content,
            )
        }

    @Test
    fun `typed operations own value encoding and record decoding`() =
        runBlocking {
            var call = 0
            val client =
                client {
                    call += 1
                    if (call == 1) {
                        """{"data":{"personCollection":{"edges":[{"node":{"uuidId":"p1","displayName":"Ada"}}]}}}"""
                    } else {
                        """
                        {"data":{"insertIntoPersonCollection":{
                          "records":[{"uuidId":"p2","displayName":"Grace"}]
                        }}}
                        """.trimIndent()
                    }
                }

            val selected =
                client.selectRecords(
                    entity = PgGraphqlEntity("Person"),
                    selection = "uuidId displayName",
                    recordDeserializer = PersonRecord.serializer(),
                    filter = PgGraphqlFilter.eq("displayName", "Ada"),
                    naming = PgGraphqlRecordNaming.SNAKE_CASE,
                )
            val inserted =
                client.insertRecords(
                    entity = PgGraphqlEntity("Person"),
                    objects = listOf(PgGraphqlObject.of("displayName" to "Grace")),
                    selection = "uuidId displayName",
                    recordDeserializer = PersonRecord.serializer(),
                    naming = PgGraphqlRecordNaming.SNAKE_CASE,
                )

            assertEquals(PersonRecord("p1", "Ada"), selected.single())
            assertEquals(PersonRecord("p2", "Grace"), inserted.single())
        }

    private fun client(response: (io.ktor.client.request.HttpRequestData) -> String): PgGraphqlClient =
        PgGraphqlClient(
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

    @Serializable
    private data class PersonRecord(
        @SerialName("_uuid_id") val id: String,
        @SerialName("display_name") val displayName: String,
    )
}
