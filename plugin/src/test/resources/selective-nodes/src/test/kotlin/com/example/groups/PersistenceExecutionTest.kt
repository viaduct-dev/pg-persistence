package com.example.groups

import dev.viaduct.persistence.runtime.db.DbClient
import dev.viaduct.persistence.runtime.db.DbRequestHeaders
import dev.viaduct.persistence.runtime.db.DbTransactionCommit
import dev.viaduct.persistence.runtime.db.DbTransactionScope
import dev.viaduct.persistence.runtime.db.DbTransactions
import dev.viaduct.persistence.runtime.db.executeImmediateTransaction
import dev.viaduct.persistence.runtime.graphql.PgGraphqlExecutor
import dev.viaduct.persistence.runtime.graphql.PgGraphqlRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import dev.viaduct.persistence.runtime.db.DbResult
import org.junit.jupiter.api.Test
import viaduct.service.BasicViaductFactory
import viaduct.service.api.ExecutionInput
import viaduct.service.api.spi.CodeInjector
import viaduct.service.api.spi.SharedTenantModuleInjectorFactory
import javax.inject.Provider
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PersistenceExecutionTest {
    @Test
    fun `composed mutation joins an immediate transaction and returns its payload`() {
        val transactionRequests = mutableListOf<PgGraphqlRequest>()
        val reads = mutableListOf<PgGraphqlRequest>()
        val id = "00000000-0000-0000-0000-000000000001"
        val dbClient =
            DbClient(
                executor = PgGraphqlExecutor { request, _ ->
                    reads += request
                    groupResult(id)
                },
                transactions = immediateTransactions(transactionRequests, id),
            )

        val result =
            viaduct(dbClient)
                .executeAsync(
                    ExecutionInput.create(
                        """mutation { addGroupComposed(input: {name: "Chess"}) { group { name } } }""",
                    ),
                ).join()

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals(mapOf("addGroupComposed" to mapOf("group" to mapOf("name" to "Chess"))), result.getData())
        assertEquals(1, transactionRequests.size)
        assertContains(transactionRequests.single().document, "insertIntoGroupCollection")
        assertEquals(1, reads.size)
        assertContains(reads.single().document, "groupCollection")
    }

    @Test
    fun `batch resolver partitions heterogeneous Viaduct selections`() {
        val requests = mutableListOf<String>()
        HttpClient(MockEngine { request ->
            val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            val query = Json.parseToJsonElement(body).jsonObject.getValue("query").jsonPrimitive.content
            requests.add(query)
            val id =
                if ("description" in query) {
                    "00000000-0000-0000-0000-000000000002"
                } else {
                    "00000000-0000-0000-0000-000000000001"
                }
            val selected =
                "\"uuidId\":\"$id\",\"name\":\"Chess\"," +
                    "\"description\":\"Weekly games\""
            val response = """{"data":{"groupCollection":{"edges":[{"node":{$selected}}]}}}"""
            respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }).use { http ->
            val dbClient = DbClient(http, "https://example.test/graphql", DbRequestHeaders { emptyMap() })
            val viaduct = viaduct(dbClient)

            val result =
                viaduct.executeAsync(
                    ExecutionInput.create(
                        """query {
                          firstGroup { name }
                          secondGroup { description }
                        }""".trimIndent(),
                    ),
                ).join()

            assertTrue(result.errors.isEmpty(), result.errors.toString())
            assertEquals(
                mapOf(
                    "firstGroup" to mapOf("name" to "Chess"),
                    "secondGroup" to mapOf("description" to "Weekly games"),
                ),
                result.getData(),
                requests.joinToString("\n\n"),
            )
            assertEquals(2, requests.size)
            assertEquals(1, requests.count { "name" in it && "description" !in it })
            assertEquals(1, requests.count { "description" in it && "name" !in it })
        }
    }

    @Test
    fun `mutation returns a node fetched using the application node resolver`() {
        val requests = mutableListOf<String>()
        val id = "00000000-0000-0000-0000-000000000001"
        HttpClient(MockEngine { request ->
            val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            val query = Json.parseToJsonElement(body).jsonObject.getValue("query").jsonPrimitive.content
            requests.add(query)
            val response = if (query.startsWith("mutation")) {
                """{"data":{"insertIntoGroupCollection":{"affectedCount":1,"records":[{"uuidId":"$id"}]}}}"""
            } else {
                """{"data":{"groupCollection":{"edges":[{"node":{"uuidId":"$id","name":"Chess"}}]}}}"""
            }
            respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }).use { http ->
            val dbClient = DbClient(http, "https://example.test/graphql", DbRequestHeaders { emptyMap() })
            val viaduct = viaduct(dbClient)
            val result = viaduct.executeAsync(
                ExecutionInput.create("""mutation { addGroup(input: {name: "Chess"}) { group { name } } }"""),
            ).join()
            assertTrue(result.errors.isEmpty(), result.errors.toString())
            assertEquals(mapOf("addGroup" to mapOf("group" to mapOf("name" to "Chess"))), result.getData())
            assertEquals(2, requests.size)
            assertContains(requests[0], "insertIntoGroupCollection")
            assertContains(requests[1], "groupCollection")
            assertContains(requests[1], "name")
        }
    }

    private fun viaduct(dbClient: DbClient) =
        BasicViaductFactory.create(
            SharedTenantModuleInjectorFactory(
                object : CodeInjector {
                    override fun <T> getProvider(clazz: Class<T>): Provider<T> = Provider {
                        clazz.cast(
                            when (clazz) {
                                GroupNodeResolver::class.java -> GroupNodeResolver(dbClient)
                                AddGroupResolver::class.java -> AddGroupResolver(dbClient)
                                AddGroupComposedResolver::class.java -> AddGroupComposedResolver(dbClient)
                                FirstGroupResolver::class.java -> FirstGroupResolver()
                                SecondGroupResolver::class.java -> SecondGroupResolver()
                                else -> error("Unexpected resolver: $clazz")
                            },
                        )
                    }
                },
            ),
        )

    private fun immediateTransactions(
        requests: MutableList<PgGraphqlRequest>,
        id: String,
    ): DbTransactions =
        object : DbTransactions {
            override fun <T> execute(
                headers: Map<String, String>,
                block: DbTransactionScope.() -> T,
            ): DbTransactionCommit<T> =
                executeImmediateTransaction(
                    execute = { request ->
                        requests += request
                        check(request.isMutation)
                        DbResult(
                            Json.parseToJsonElement(
                                """{"operation0":{"affectedCount":1,"records":[{"uuidId":"$id"}]}}""",
                            ).jsonObject,
                        )
                    },
                    block = block,
                )
        }

    private fun groupResult(id: String): DbResult<kotlinx.serialization.json.JsonObject> =
        DbResult(
            Json.parseToJsonElement(
                """{"groupCollection":{"edges":[{"node":{"uuidId":"$id","name":"Chess"}}]}}""",
            ).jsonObject,
        )
}
