package dev.viaduct.persistence.runtime.db

import dev.viaduct.persistence.runtime.graphql.GraphqlQuery
import dev.viaduct.persistence.runtime.graphql.PgGraphqlTransport
import dev.viaduct.persistence.runtime.graphql.parseError
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

internal sealed interface RetryableTransactionReply {
    class Committed(
        val result: DbTransactionResult,
    ) : RetryableTransactionReply

    class RolledBack(
        errors: List<UpstreamGraphqlError>,
        val sqlState: String?,
    ) : RetryableTransactionReply {
        val errors: List<UpstreamGraphqlError> = java.util.List.copyOf(errors)
    }

    data object Busy : RetryableTransactionReply

    data object Mismatch : RetryableTransactionReply
}

/** Owns the pg_graphql wire format; callers receive decoded transaction outcomes, not JSON. */
internal class RetryableTransactionProtocol(
    private val transport: PgGraphqlTransport,
) {
    suspend fun execute(
        headers: Map<String, String>,
        prepared: DbPreparedTransaction,
    ): RetryableTransactionReply {
        val reply =
            send(headers, EXECUTE_QUERY, EXECUTE_FIELD, Variables(prepared.operationId, prepared.request.toString()))
        return when (reply.status) {
            COMMITTED -> RetryableTransactionReply.Committed(decodeCommitted(prepared, reply.response))
            ROLLED_BACK ->
                RetryableTransactionReply.RolledBack(
                    reply.errors.map(::parseError).let {
                        if (it.isEmpty()) listOf(UpstreamGraphqlError("Transaction rolled back")) else it
                    },
                    reply.code,
                )
            BUSY -> RetryableTransactionReply.Busy
            MISMATCH -> RetryableTransactionReply.Mismatch
            else -> throw DbTransactionException(DbTransactionOutcome.UNKNOWN, prepared, "Invalid transaction response")
        }
    }

    suspend fun lookup(
        headers: Map<String, String>,
        operationId: String,
        scope: String,
    ): DbRecoveredTransaction? {
        val reply = send(headers, LOOKUP_QUERY, LOOKUP_FIELD, Variables(operationId))
        if (reply.status == NOT_FOUND) return null
        require(reply.status == COMMITTED) { "Invalid lookup response" }
        val response = reply.response.jsonObject
        val prepared =
            DbPreparedTransaction.create(
                operationId,
                scope,
                reply.request.jsonObject,
                response.getValue("data").jsonObject.size,
            )
        return DbRecoveredTransaction(prepared, decodeCommitted(prepared, response))
    }

    private suspend fun send(
        headers: Map<String, String>,
        document: String,
        field: String,
        variables: Variables,
    ): Envelope {
        val query =
            GraphqlQuery(
                document.trimIndent(),
                json.encodeToJsonElement(Variables.serializer(), variables).jsonObject,
                field,
            )
        val result = requireNotNull(transport.executeElementResult(headers, query))
        val value = result.strict(field)
        // pg_graphql's JSON scalar is a JSON-encoded string, not an embedded GraphQL object.
        val decoded = if (value is JsonPrimitive && value.isString) json.parseToJsonElement(value.content) else value
        return json.decodeFromJsonElement(Envelope.serializer(), decoded)
    }

    private fun decodeCommitted(
        prepared: DbPreparedTransaction,
        response: JsonElement,
    ): DbTransactionResult =
        try {
            prepared.decodeResponse(response.jsonObject)
        } catch (exception: IllegalArgumentException) {
            throw DbTransactionException(
                DbTransactionOutcome.COMMITTED,
                prepared,
                "Transaction committed but its result could not be decoded",
                exception,
            )
        }

    @Serializable
    private data class Variables(
        val operationId: String,
        val request: String? = null,
    )

    @Serializable
    private data class Envelope(
        val status: String? = null,
        val request: JsonElement = JsonNull,
        val response: JsonElement = JsonNull,
        val errors: List<JsonObject> = emptyList(),
        val code: String? = null,
    )

    companion object {
        const val SCOPE_HEADER = "x-pg-persistence-scope"
        private const val EXECUTE_FIELD = "pgPersistenceExecuteTransaction"
        private const val LOOKUP_FIELD = "pgPersistenceLookupTransaction"
        private const val COMMITTED = "committed"
        private const val ROLLED_BACK = "rolledBack"
        private const val BUSY = "busy"
        private const val MISMATCH = "mismatch"
        private const val NOT_FOUND = "notFound"
        private val json = Json { ignoreUnknownKeys = true }
        private const val EXECUTE_QUERY = """
            mutation RetryTransaction(${'$'}operationId: String!, ${'$'}request: JSON!) {
                $EXECUTE_FIELD(operationId: ${'$'}operationId, request: ${'$'}request)
            }
        """
        private const val LOOKUP_QUERY = """
            query LookupTransaction(${'$'}operationId: String!) {
                $LOOKUP_FIELD(operationId: ${'$'}operationId)
            }
        """
    }
}
