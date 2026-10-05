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
                    """{"data":{"personCollection":{"edges":[{"node":{"uuidId":"p1"}}],
                      "pageInfo":{"hasNextPage":false,"endCursor":null}}}}"""
                }

            val records =
                client.select(
                    entity = PgGraphqlEntity("Person"),
                    selection = "uuidId",
                    filter = buildJsonObject { put("active", buildJsonObject { put("eq", true) }) },
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
                "query Select(\$filter: PersonFilter, \$after: Cursor, \$orderBy: [PersonOrderBy!]) { " +
                    "personCollection(filter: \$filter, after: \$after, orderBy: \$orderBy) { " +
                    "edges { node { uuidId } } pageInfo { hasNextPage endCursor } } }",
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
                        """{"data":{"personCollection":{"edges":[{"node":{"uuidId":"p1","displayName":"Ada"}}],
                          "pageInfo":{"hasNextPage":false,"endCursor":null}}}}"""
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

    @Test
    fun `unpaged selects drain provider pages without exposing cursors`() =
        runBlocking {
            var calls = 0
            val api =
                client {
                    calls++
                    val more = calls == 1
                    """{"data":{"personCollection":{
              "edges":[{"node":{"uuidId":"p$calls"}}],
              "pageInfo":{"hasNextPage":$more,"endCursor":"native-cursor"}
            }}}"""
                }
            assertEquals(
                listOf("p1", "p2"),
                api
                    .select(PgGraphqlEntity("Person"), "uuidId")
                    .map {
                        it.jsonObject
                            .getValue("uuidId")
                            .jsonPrimitive.content
                    },
            )
        }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = ["cycle", "empty", "missing"])
    fun `unpaged selects reject stalled provider pagination`(case: String) =
        runBlocking<Unit> {
            val edges = if (case == "empty") "[]" else """[{"node":{"uuidId":"p1"}}]"""
            val cursor = if (case == "missing") "null" else "\"native-cursor\""
            val api =
                client {
                    """{"data":{"personCollection":{"edges":$edges,
              "pageInfo":{"hasNextPage":true,"endCursor":$cursor}}}}"""
                }
            kotlin.test.assertFailsWith<IllegalStateException> {
                kotlinx.coroutines.withTimeout(1000) { api.select(PgGraphqlEntity("Person"), "uuidId") }
            }
        }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
        strings = [
            "{ personCollection(first: 1) { edges { node { uuidId } } } }",
            "{ personCollection(after: \"cursor\") { edges { node { uuidId } } } }",
            "{ personCollection { pageInfo { endCursor } } }",
            "{ personCollection { edges { c: cursor } } }",
            "query { personCollection { ...Fields } } fragment Fields on PersonConnection { edges { cursor } }",
            "{ personCollection { edges { node { friends { edges { cursor } } } } } }",
        ],
    )
    fun `raw operations cannot expose native database paging`(document: String) =
        runBlocking<Unit> {
            val api = client { error("Request should not be executed") }
            kotlin.test.assertFailsWith<IllegalArgumentException> {
                api.execute(document, responseKey = "personCollection")
            }
        }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = ["select", "insert", "update", "delete"])
    fun `record projections cannot provide native paging`(operation: String) =
        runBlocking<Unit> {
            val api = client { error("Request should not be executed") }
            val projection = "friends(first: 1) { edges { cursor } }"
            kotlin.test.assertFailsWith<IllegalArgumentException> {
                when (operation) {
                    "select" -> api.select(PgGraphqlEntity("Person"), projection)
                    "insert" ->
                        api.insertRecords(
                            PgGraphqlEntity("Person"),
                            emptyList(),
                            projection,
                            PersonRecord.serializer(),
                        )
                    "update" ->
                        api.updateRecords(
                            PgGraphqlEntity("Person"),
                            PgGraphqlObject.of(),
                            PgGraphqlFilter.empty(),
                            1,
                            projection,
                            PersonRecord.serializer(),
                        )
                    else ->
                        PgGraphqlMutationClient(
                            dev.viaduct.persistence.runtime.graphql.PgGraphqlExecutor { _, _ ->
                                error("Request should not be executed")
                            },
                        ).delete(
                            PgGraphqlEntity("Person"),
                            buildJsonObject {},
                            1,
                            "records { $projection }",
                        )
                }
            }
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
