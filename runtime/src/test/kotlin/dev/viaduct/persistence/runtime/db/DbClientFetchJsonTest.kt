package dev.viaduct.persistence.runtime.db

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import viaduct.api.context.ExecutionContext
import viaduct.api.reflect.Type
import viaduct.api.select.OutputSelectionFragment
import viaduct.api.select.SelectionSet
import viaduct.api.types.NodeObject
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DbClientFetchJsonTest {
    @Test
    fun `fetchJsonResult preserves partial data and structured upstream errors`() =
        runBlocking {
            val selections = mockk<SelectionSet<FetchJsonFixtureNode>>()
            every { selections.isEmpty() } returns false
            every { selections.type } returns FetchJsonFixtureType
            every { selections.toFragment() } returns
                OutputSelectionFragment(
                    "Main",
                    "fragment Main on FetchJsonFixtureNode { status }",
                    emptyMap(),
                )
            val engine =
                MockEngine {
                    respond(
                        content =
                            """
                            {
                              "data": {"group": {"status": null}},
                              "errors": [{
                                "message": "status failed",
                                "path": ["group", "status"],
                                "locations": [{"line": 1, "column": 8}],
                                "extensions": {"code": "UPSTREAM"}
                              }]
                            }
                            """.trimIndent(),
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client = DbClient(HttpClient(engine), "https://example.test/graphql/v1")

            val result =
                client.fetchJsonResult(
                    mockk<ExecutionContext>(),
                    DbRead(DbRoot("group")),
                    selections,
                )

            assertIs<JsonNull>(result.data?.get("status"))
            val error = result.errors.single()
            assertEquals("status failed", error.message)
            assertEquals(listOf("group", "status"), error.path.map { it.jsonPrimitive.content })
            assertEquals(UpstreamGraphqlLocation(1, 8), error.locations.single())
            assertEquals("UPSTREAM", error.extensions["code"]?.jsonPrimitive?.content)
        }

    @Test
    fun `fetchJson returns raw pg_graphql JSON without converting it to a GRT`() =
        runBlocking {
            val selections = mockk<SelectionSet<FetchJsonFixtureNode>>()
            every { selections.isEmpty() } returns false
            every { selections.type } returns FetchJsonFixtureType
            every { selections.toFragment() } returns
                OutputSelectionFragment(
                    "Main",
                    "fragment Main on FetchJsonFixtureNode { status }",
                    emptyMap(),
                )
            val engine =
                MockEngine {
                    respond(
                        content = """{"data":{"group":{"status":"ACTIVE"}}}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client = DbClient(httpClient = HttpClient(engine), endpoint = "https://example.test/graphql/v1")

            // FetchJsonFixtureNode is a plain reflection fixture, not a real generated GRT:
            // converting this result with toGRT would fail, so a passing assertion here proves
            // fetchJson never attempts that conversion.
            val json = client.fetchJson(mockk<ExecutionContext>(), DbRead(DbRoot("group")), selections)

            assertEquals("ACTIVE", json["status"]?.jsonPrimitive?.content)
        }

    @Test
    fun `filtered single result validates and returns paths after unwrapping the node`() =
        runBlocking {
            val selections = mockk<SelectionSet<FetchJsonFixtureNode>>()
            every { selections.isEmpty() } returns false
            every { selections.type } returns FetchJsonFixtureType
            every { selections.toFragment() } returns
                OutputSelectionFragment(
                    "Main",
                    "fragment Main on FetchJsonFixtureNode { status }",
                    emptyMap(),
                )
            val engine =
                MockEngine {
                    respond(
                        content =
                            """
                            {"data":{"group":{"edges":[{"node":{"status":null}}]}},
                             "errors":[{"message":"failed","path":["group","edges",0,"node","status"]}]}
                            """.trimIndent(),
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client = DbClient(HttpClient(engine), "https://example.test/graphql/v1")

            val result =
                client.fetchJsonResult(
                    mockk<ExecutionContext>(),
                    DbRead(DbRoot("group", singleViaFilteredCollection = true)),
                    selections,
                )

            assertIs<JsonNull>(result.data?.get("status"))
            assertEquals(
                listOf("group", "status"),
                result.errors
                    .single()
                    .path
                    .map { it.jsonPrimitive.content },
            )
        }

    @Test
    fun `preserves a pathless request error`() =
        runBlocking {
            val selections = selections()
            val client =
                clientFor(
                    """{"data":{"group":{"status":null}},"errors":[{"message":"request failed"}]}""",
                )

            val result = client.fetchJsonResult(mockk(), DbRead(DbRoot("group")), selections)

            assertEquals(emptyList(), result.errors.single().path)
        }

    @Test
    fun `filtered result preserves errors when null bubbling removes the node`() =
        runBlocking {
            val selections = selections()
            val client =
                clientFor(
                    """
                    {"data":{"group":{"edges":[{"node":null}]}},
                     "errors":[{"message":"failed","path":["group","edges",0,"node","status"]}]}
                    """.trimIndent(),
                )

            val result =
                client.fetchJsonResult(
                    mockk(),
                    DbRead(DbRoot("group", singleViaFilteredCollection = true)),
                    selections,
                )

            assertEquals(null, result.data)
            assertEquals(
                listOf("group", "status"),
                result.errors
                    .single()
                    .path
                    .map { it.jsonPrimitive.content },
            )
        }

    private fun selections(): SelectionSet<FetchJsonFixtureNode> =
        mockk<SelectionSet<FetchJsonFixtureNode>>().also { selections ->
            every { selections.isEmpty() } returns false
            every { selections.type } returns FetchJsonFixtureType
            every { selections.toFragment() } returns
                OutputSelectionFragment(
                    "Main",
                    "fragment Main on FetchJsonFixtureNode { status }",
                    emptyMap(),
                )
        }

    private fun clientFor(response: String): DbClient =
        DbClient(
            HttpClient(
                MockEngine {
                    respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                },
            ),
            "https://example.test/graphql/v1",
        )
}

internal class FetchJsonFixtureNode : NodeObject {
    object Fields
}

internal object FetchJsonFixtureType : Type<FetchJsonFixtureNode> {
    override val name: String = "FetchJsonFixtureNode"
    override val kcls: KClass<out FetchJsonFixtureNode> = FetchJsonFixtureNode::class
}
