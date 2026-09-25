package dev.viaduct.persistence.runtime.graphql
import dev.viaduct.persistence.runtime.db.DbRequestHeaders
import dev.viaduct.persistence.runtime.db.DbResult
import dev.viaduct.persistence.runtime.db.UpstreamGraphqlError
import dev.viaduct.persistence.runtime.db.UpstreamGraphqlException
import dev.viaduct.persistence.runtime.db.UpstreamGraphqlLocation
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.util.reflect.typeInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
    private val httpClient: HttpClient,
    private val endpoint: String,
    private val requestHeaders: DbRequestHeaders,
) {
    private val json = Json { ignoreUnknownKeys = true }

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
    ): GraphqlEnvelope {
        val response =
            httpClient.post(endpoint) {
                headers.forEach { (name, value) ->
                    header(name, value)
                }
                setBody(
                    TextContent(
                        text =
                            json.encodeToString(
                                GraphqlRequest.serializer(),
                                GraphqlRequest(query.text, query.variables),
                            ),
                        contentType = ContentType.Application.Json,
                    ),
                    typeInfo<TextContent>(),
                )
            }
        val envelope = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val data = envelope["data"] as? JsonObject
        val errors =
            (envelope["errors"] as? JsonArray)
                ?.map { parseError(it.jsonObject) }
                .orEmpty()
        return GraphqlEnvelope(data, errors)
    }

    private fun parseError(error: JsonObject): UpstreamGraphqlError =
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
}

private data class GraphqlEnvelope(
    val data: JsonObject?,
    val errors: List<UpstreamGraphqlError>,
)

@Serializable
private data class GraphqlRequest(
    val query: String,
    val variables: JsonElement,
)
