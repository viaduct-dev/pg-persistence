package com.example.groups

import dev.viaduct.persistence.runtime.db.DbClient
import dev.viaduct.persistence.runtime.db.BlockingDbTransactions
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
import kotlinx.coroutines.runBlocking
import dev.viaduct.persistence.runtime.db.DbResult
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import viaduct.service.BasicViaductFactory
import viaduct.service.api.ExecutionInput
import viaduct.service.api.spi.CodeInjector
import viaduct.service.api.spi.SharedTenantModuleInjectorFactory
import javax.inject.Provider
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Timeout(120)
class PersistenceExecutionTest {
    @Test
    fun `named lookup uses generated selective connection contexts and returns duplicate edges`() {
        val id = "00000000-0000-0000-0000-000000000001"
        val client = DbClient(PgGraphqlExecutor { request, _ ->
            val rows = if ("first: 1" in request.document) 1 else 2
            val edges = List(rows) { """{"node":{"uuidId":"$id"}}""" }.joinToString(",")
            DbResult(Json.parseToJsonElement("""{"groupCollection":{
              "edges":[$edges],
              "pageInfo":{"hasNextPage":false,"endCursor":null}
            }}""").jsonObject)
        })
        val result = runBlocking {
            viaduct(client).execute(ExecutionInput.create("""{
              one: lookupGroups(name: "Chess", first: 1) { edges { cursor node { id } } }
              two: lookupGroups(name: "Chess", first: 2) { edges { cursor node { id } } }
            }"""))
        }
        val globalId = viaduct.service.api.spi.globalid.GlobalIDCodecDefault.serialize("Group", id)
        val edges = (0..1).map { offset ->
            mapOf("cursor" to viaduct.api.types.OffsetCursor.fromOffset(offset).value, "node" to mapOf("id" to globalId))
        }
        assertEquals<Any?>(
            emptyList<Any>() to mapOf("one" to mapOf("edges" to edges.take(1)), "two" to mapOf("edges" to edges)),
            result.errors to result.getData(),
        )
    }

    @Test
    fun `single and generated batch reads enforce the same semantic non-null policy`() {
        val executor = PgGraphqlExecutor { _, _ ->
            DbResult(Json.parseToJsonElement("""{"groupCollection":{"edges":[{"node":{"uuidId":"00000000-0000-0000-0000-000000000001","name":null,"description":"Weekly games"}}]}}""").jsonObject)
        }
        val client = DbClient(executor)
        val selections = io.mockk.mockk<viaduct.api.select.SelectionSet<viaduct.api.grts.Group>>()
        io.mockk.every { selections.type } returns viaduct.api.grts.Group.Reflection
        io.mockk.every { selections.isEmpty() } returns false
        io.mockk.every { selections.toFragment() } returns viaduct.api.select.OutputSelectionFragment("Main", "fragment Main on Group { name }", emptyMap())
        val single = runBlocking {
            client.fetchJsonResult(io.mockk.mockk(), dev.viaduct.persistence.runtime.db.DbRead(dev.viaduct.persistence.runtime.db.DbRoot("groupCollection", singleViaFilteredCollection = true)), selections)
        }
        val batch = runBlocking { viaduct(client).execute(ExecutionInput.create("{ firstGroup { name } }")) }
        val expected = listOf("Semantic non-null field 'Group.name' returned null without an error" to "SEMANTIC_NON_NULL_VIOLATION")
        assertEquals(
            listOf(expected, expected),
            listOf(
                single.errors.map { it.message to it.extensions["code"]?.jsonPrimitive?.content },
                batch.errors.map { it.message to it.extensions?.get("code") },
            ),
        )
    }

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

        val result = runBlocking {
            viaduct(dbClient)
                .execute(
                    ExecutionInput.create(
                        """mutation { addGroupComposed(input: {name: "Chess"}) { group { name } } }""",
                    ),
                )
        }

        assertEquals(
            ObservedExecution(
                errors = emptyList<Any>(),
                data = mapOf("addGroupComposed" to mapOf("group" to mapOf("name" to "Chess"))),
                transactionMutations = listOf(true),
                ordinaryReads = listOf(true),
            ),
            ObservedExecution(
                errors = result.errors,
                data = result.getData(),
                transactionMutations = transactionRequests.map { "insertIntoGroupCollection" in it.document },
                ordinaryReads = reads.map { "groupCollection" in it.document },
            ),
        )
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

            val result = runBlocking {
                viaduct.execute(
                    ExecutionInput.create(
                        """query {
                          firstGroup { name }
                          secondGroup { description }
                        }""".trimIndent(),
                    ),
                )
            }

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
    fun `generic Node and concrete Group selections resolve the same batched node`() {
        val id = "00000000-0000-0000-0000-000000000001"
        val client = DbClient(PgGraphqlExecutor { _, _ -> groupResult(id) })
        val globalId = viaduct.service.api.spi.globalid.GlobalIDCodecDefault.serialize("Group", id)
        val result = runBlocking {
            viaduct(client).execute(ExecutionInput.create(
                """query {
                  firstGroup { id name }
                  node(id: "$globalId") { ... on Group { id name } }
                }""",
            ))
        }
        val group = mapOf("id" to globalId, "name" to "Chess")
        assertEquals<Any?>(
            emptyList<Any>() to mapOf("firstGroup" to group, "node" to group),
            result.errors to result.getData(),
        )
    }

    @Test
    fun `generated node contexts hydrate requested references omitted from owned selections`() {
        val id = "00000000-0000-0000-0000-000000000001"
        val related = "00000000-0000-0000-0000-000000000002"
        val client = DbClient(PgGraphqlExecutor { request, _ ->
            if (related in request.variables.getValue("ids").toString()) groupResult(related) else DbResult(Json.parseToJsonElement("""{
              "groupCollection":{"edges":[{"node":{
                "uuidId":"$id", "name":"Chess", "_viaduct_ref_parent":"$related",
                "members":{"edges":[{"node":{"uuidId":"$related"}}],"pageInfo":{"hasNextPage":false}}
              }}]}
            }""").jsonObject)
        })
        val result = runBlocking {
            viaduct(client).execute(ExecutionInput.create("{ firstGroup { name parent { id } members { id } } }"))
        }
        val relatedId = viaduct.service.api.spi.globalid.GlobalIDCodecDefault.serialize("Group", related)
        assertEquals<Any?>(
            emptyList<Any>() to mapOf("firstGroup" to mapOf(
                "name" to "Chess", "parent" to mapOf("id" to relatedId), "members" to listOf(mapOf("id" to relatedId)),
            )),
            result.errors to result.getData(),
        )
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
            val result = runBlocking {
                viaduct.execute(
                    ExecutionInput.create("""mutation { addGroup(input: {name: "Chess"}) { group { name } } }"""),
                )
            }
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
                                LookupGroupsResolver::class.java -> LookupGroupsResolver(dbClient)
                                LookupOwnerNodeResolver::class.java -> LookupOwnerNodeResolver(dbClient)
                                OwnerGroupsResolver::class.java -> OwnerGroupsResolver(dbClient)
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
        object : BlockingDbTransactions() {
            override fun <T> executeBlocking(
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

    private data class ObservedExecution(
        val errors: List<*>,
        val data: Any?,
        val transactionMutations: List<Boolean>,
        val ordinaryReads: List<Boolean>,
    )
}
