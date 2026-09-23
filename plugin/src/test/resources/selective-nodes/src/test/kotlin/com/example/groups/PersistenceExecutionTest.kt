package com.example.groups

import dev.viaduct.persistence.runtime.db.DbClient
import dev.viaduct.persistence.runtime.db.DbRequestHeaders
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
            val injector = object : CodeInjector {
                override fun <T> getProvider(clazz: Class<T>): Provider<T> = Provider {
                    clazz.cast(when (clazz) {
                        GroupNodeResolver::class.java -> GroupNodeResolver(dbClient)
                        AddGroupResolver::class.java -> AddGroupResolver(dbClient)
                        else -> error("Unexpected resolver: $clazz")
                    })
                }
            }
            val viaduct = BasicViaductFactory.create(SharedTenantModuleInjectorFactory(injector))
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
}
