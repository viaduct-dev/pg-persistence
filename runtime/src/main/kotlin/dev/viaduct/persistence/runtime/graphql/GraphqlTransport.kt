package dev.viaduct.persistence.runtime.graphql
import dev.viaduct.persistence.runtime.db.DbRequestHeaders
import dev.viaduct.persistence.runtime.db.DbResult
import dev.viaduct.persistence.runtime.db.UpstreamGraphqlError
import dev.viaduct.persistence.runtime.db.UpstreamGraphqlException
import dev.viaduct.persistence.runtime.db.UpstreamGraphqlLocation
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A prepared GraphQL operation and the response field that contains its result. */
internal data class GraphqlQuery(
    val text: String,
    val variables: JsonElement,
    val responseKey: String,
)

/** Sends GraphQL operations and converts provider envelopes into db JSON objects. */
internal class PgGraphqlTransport(
    private val executor: PgGraphqlExecutor,
    private val requestHeaders: DbRequestHeaders,
) {
    constructor(httpClient: HttpClient, endpoint: String, requestHeaders: DbRequestHeaders) :
        this(HttpPgGraphqlExecutor(httpClient, endpoint), requestHeaders)

    suspend fun execute(
        context: viaduct.api.context.ExecutionContext,
        query: GraphqlQuery,
    ): JsonObject {
        val result = executeResult(context, query)
        if (result.errors.isNotEmpty()) throw UpstreamGraphqlException(result.errors)
        return result.data ?: error("Db response did not include '${query.responseKey}'")
    }

    suspend fun executeResult(
        context: viaduct.api.context.ExecutionContext,
        query: GraphqlQuery,
    ): DbResult<JsonObject> = executeResult(requestHeaders.forContext(context), query)

    suspend fun executeResult(
        headers: Map<String, String>,
        query: GraphqlQuery,
    ): DbResult<JsonObject> {
        val result = executeElementResult(headers, query)
        return DbResult(result.data as? JsonObject, result.errors)
    }

    suspend fun executeElementResult(
        headers: Map<String, String>,
        query: GraphqlQuery,
    ): DbResult<JsonElement> {
        val envelope = executeEnvelope(headers, query)
        return DbResult(envelope.data?.get(query.responseKey), envelope.errors)
    }

    suspend fun executeRootResult(
        context: viaduct.api.context.ExecutionContext,
        query: GraphqlQuery,
    ): DbResult<JsonObject> = executeRootResult(requestHeaders.forContext(context), query)

    private suspend fun executeRootResult(
        headers: Map<String, String>,
        query: GraphqlQuery,
    ): DbResult<JsonObject> {
        val envelope = executeEnvelope(headers, query)
        return DbResult(envelope.data, envelope.errors)
    }

    private suspend fun executeEnvelope(
        headers: Map<String, String>,
        query: GraphqlQuery,
    ): DbResult<JsonObject> = executor.execute(PgGraphqlRequest(query.text, query.variables.jsonObject), headers)
}

internal fun parseError(error: JsonObject): UpstreamGraphqlError =
    UpstreamGraphqlError(
        message = error["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown upstream GraphQL error",
        path = error["path"]?.jsonArray?.toList().orEmpty(),
        locations =
            (error["locations"] as? JsonArray)
                ?.mapNotNull { location ->
                    val value = location as? JsonObject ?: return@mapNotNull null
                    val line = value["line"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                    val column = value["column"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                    if (line == null || column == null) null else UpstreamGraphqlLocation(line, column)
                }.orEmpty(),
        extensions = error["extensions"] as? JsonObject ?: JsonObject(emptyMap()),
    )
