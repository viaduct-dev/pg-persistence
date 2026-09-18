package dev.viaduct.persistence.runtime.graphql

import dev.viaduct.persistence.runtime.db.DbResult
import dev.viaduct.persistence.runtime.db.UpstreamGraphqlError
import dev.viaduct.persistence.runtime.db.UpstreamGraphqlLocation
import dev.viaduct.persistence.runtime.db.immutableJsonObjectValues
import graphql.language.OperationDefinition
import graphql.parser.Parser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Executes a pg_graphql request without changing input conversion or result mapping. */
fun interface PgGraphqlExecutor {
    suspend fun execute(
        request: PgGraphqlRequest,
        headers: Map<String, String>,
    ): DbResult<JsonObject>
}

/** GraphQL document and variables shared by HTTP and JDBC execution. */
class PgGraphqlRequest(
    val document: String,
    variables: JsonObject = JsonObject(emptyMap()),
    val operationName: String? = null,
) {
    private val variableValues = immutableJsonObjectValues(variables)
    val variables: JsonObject get() = JsonObject(variableValues)

    /** Select the requested operation, rejecting ambiguous documents before JDBC execution. */
    val isMutation: Boolean
        get() {
            val operations = Parser().parseDocument(document).getDefinitionsOfType(OperationDefinition::class.java)
            val operation = operations.single { operationName == null || it.name == operationName }
            return operation.operation == OperationDefinition.Operation.MUTATION
        }
}

/** Decode the common pg_graphql envelope, retaining partial query data and structured errors. */
fun decodePgGraphqlResponse(response: String): DbResult<JsonObject> {
    val envelope = Json.parseToJsonElement(response).jsonObject
    val errors = envelope["errors"]?.let { it as JsonArray }?.map { parseError(it.jsonObject) }.orEmpty()
    val data = envelope["data"]?.let { if (it == kotlinx.serialization.json.JsonNull) null else it.jsonObject }
    require(data != null || errors.isNotEmpty()) { "pg_graphql response contains neither data nor errors" }
    return DbResult(data, errors)
}

/** Store a GraphQL result as JSON without requiring Java serialization of its Kotlin values. */
fun encodePgGraphqlResponse(result: DbResult<JsonObject>): String =
    buildJsonObject {
        result.data?.let { put("data", it) }
        if (result.errors.isNotEmpty()) {
            put("errors", JsonArray(result.errors.map(::encodeError)))
        }
    }.toString()

private fun encodeError(error: UpstreamGraphqlError): JsonObject =
    buildJsonObject {
        put("message", error.message)
        put("path", JsonArray(error.path))
        put("extensions", error.extensions)
        put("locations", JsonArray(error.locations.map(::encodeLocation)))
    }

private fun encodeLocation(location: UpstreamGraphqlLocation): JsonObject =
    buildJsonObject {
        put("line", location.line)
        put("column", location.column)
    }
