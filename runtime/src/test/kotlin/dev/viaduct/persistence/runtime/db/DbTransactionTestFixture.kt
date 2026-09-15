package dev.viaduct.persistence.runtime.db

import assertk.assertThat
import assertk.assertions.isEqualTo
import dev.viaduct.persistence.runtime.graphql.PgGraphqlTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import viaduct.api.context.ExecutionContext
import viaduct.api.reflect.Type

internal class DbTransactionTestFixture(
    responseBody: String = SUCCESS_RESPONSE,
) {
    private val recorder = RequestRecorder(responseBody)
    private val transaction: DbTransaction

    init {
        val transport =
            PgGraphqlTransport(
                HttpClient(
                    MockEngine { request ->
                        recorder.record(request)
                        respond(recorder.response(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ),
                "https://example.test/graphql/v1",
                DbRequestHeaders { mapOf(HttpHeaders.Authorization to "Bearer token") },
            )
        transaction = DbTransaction(transport, mockk<ExecutionContext>())
    }

    fun entity(name: String): DbTransactionEntity<FixtureNode> {
        val type = mockk<Type<FixtureNode>>()
        every { type.name } returns name
        return DbTransactionEntity(transaction, type)
    }

    fun requests(): List<JsonObject> = recorder.requests()

    suspend fun commit(): DbTransactionResult = transaction.commit()

    suspend fun commitResult(): DbResult<DbTransactionResult> = transaction.commitResult()

    suspend fun <T> execute(block: DbTransactionScope.() -> T): DbTransactionCommit<T> = transaction.execute(block)

    fun abort() = transaction.abort()

    fun addMixedOperations(): List<DbTransactionOperation> {
        val inserted =
            entity("Group").insert(
                PgGraphqlObject.of("uuidId" to "group-1", "name" to "Chess"),
            )
        val updated =
            entity("GroupMember").update(
                PgGraphqlUpdate(
                    PgGraphqlObject.of("role" to "ADMIN"),
                    PgGraphqlFilter.eq("uuidId", "member-1"),
                    atMost = 25,
                ),
            )
        entity("GroupMember").delete(
            PgGraphqlDelete(PgGraphqlFilter.eq("uuidId", "member-2")),
        )
        return listOf(inserted, updated)
    }

    companion object {
        const val PARTIAL_RESPONSE =
            """{"data":{"operation0":{"affectedCount":1,"records":[{"uuidId":"group-1"}]},""" +
                """"operation1":null},"errors":[{"message":"member failed","path":["operation1"]}]}"""

        private const val SUCCESS_RESPONSE =
            """{"data":{"operation0":{"affectedCount":1,"records":[{"uuidId":"group-1"}]},""" +
                """"operation1":{"affectedCount":1,"records":[{"uuidId":"member-1"}]},""" +
                """"operation2":{"affectedCount":1,"records":[]}}}"""
    }
}

private class RequestRecorder(
    private val responseBody: String,
) {
    private val requests = mutableListOf<JsonObject>()

    fun record(request: HttpRequestData) {
        assertThat(request.headers[HttpHeaders.Authorization]).isEqualTo("Bearer token")
        requests += request.bodyJson()
    }

    fun requests(): List<JsonObject> = requests.toList()

    fun response(): String = responseBody
}

internal fun JsonObject.recordIds(): List<String> =
    getValue("records").let { records ->
        (records as kotlinx.serialization.json.JsonArray).map {
            it.jsonObject
                .getValue("uuidId")
                .jsonPrimitive.content
        }
    }

private fun OutgoingContent.bodyText(): String =
    when (this) {
        is OutgoingContent.ByteArrayContent -> bytes().decodeToString()
        else -> error("Unexpected request body: ${this::class}")
    }

private fun HttpRequestData.bodyJson(): JsonObject = Json.parseToJsonElement(body.bodyText()).jsonObject
