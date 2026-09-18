package dev.viaduct.persistence.runtime.graphql

import dev.viaduct.persistence.runtime.db.DbResult
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.util.reflect.typeInfo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Sends GraphQL over HTTP with the caller's per-request headers. The caller owns the HTTP client. */
class HttpPgGraphqlExecutor(
    private val httpClient: HttpClient,
    private val endpoint: String,
) : PgGraphqlExecutor {
    override suspend fun execute(
        request: PgGraphqlRequest,
        headers: Map<String, String>,
    ): DbResult<JsonObject> {
        val body =
            buildJsonObject {
                put("query", request.document)
                put("variables", request.variables)
                request.operationName?.let { put("operationName", it) }
            }
        val response =
            httpClient.post(endpoint) {
                headers.forEach { (name, value) -> header(name, value) }
                setBody(TextContent(body.toString(), ContentType.Application.Json), typeInfo<TextContent>())
            }
        return decodePgGraphqlResponse(response.bodyAsText())
    }
}
