package dev.viaduct.persistence.runtime.db

import dev.viaduct.persistence.runtime.graphql.GraphqlQuery
import dev.viaduct.persistence.runtime.graphql.PgGraphqlTransport
import graphql.schema.GraphQLInputObjectType
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Executes pg_graphql's generated Relay-style insert, update, and delete operations.
 *
 * This client is independent of a Viaduct [viaduct.api.context.ExecutionContext]. Applications
 * pass authentication headers for each call, which makes it suitable for mutation resolvers that
 * already own authentication and domain-error handling.
 */
@Suppress("LongParameterList")
class PgGraphqlMutationClient(
    httpClient: HttpClient,
    endpoint: String,
) {
    private val transport = PgGraphqlTransport(httpClient, endpoint, DbRequestHeaders { emptyMap() })

    /** Inserts one explicitly constructed pg_graphql object. */
    suspend fun insert(
        entity: PgGraphqlEntity,
        objectValue: PgGraphqlObject,
        selection: String = "affectedCount",
        headers: Map<String, String> = emptyMap(),
    ): JsonObject = insert(entity, buildJsonArray { add(objectValue.encoded()) }, selection, headers)

    /** Inserts [objects] and returns the selected pg_graphql mutation payload. */
    suspend fun insert(
        entity: PgGraphqlEntity,
        objects: JsonArray,
        selection: String = "affectedCount",
        headers: Map<String, String> = emptyMap(),
    ): JsonObject = insertResult(entity, objects, selection, headers).strict(entity.insertField)

    /** Inserts [objects], preserving partial payload data and structured upstream errors. */
    suspend fun insertResult(
        entity: PgGraphqlEntity,
        objects: JsonArray,
        selection: String = "affectedCount",
        headers: Map<String, String> = emptyMap(),
    ): DbResult<JsonObject> =
        execute(
            entity.insertField,
            "mutation Insert(${'$'}objects: [${entity.typeName}InsertInput!]!) { " +
                "${entity.insertField}(objects: ${'$'}objects) { $selection } }",
            buildJsonObject { put("objects", objects) },
            headers,
        )

    /** Updates matching rows and returns the selected pg_graphql mutation payload. */
    suspend fun update(
        entity: PgGraphqlEntity,
        set: JsonObject,
        filter: JsonObject,
        atMost: Int,
        selection: String = "affectedCount",
        headers: Map<String, String> = emptyMap(),
    ): JsonObject = updateResult(entity, set, filter, atMost, selection, headers).strict(entity.updateField)

    /** Updates matching rows, preserving partial payload data and structured upstream errors. */
    suspend fun updateResult(
        entity: PgGraphqlEntity,
        set: JsonObject,
        filter: JsonObject,
        atMost: Int,
        selection: String = "affectedCount",
        headers: Map<String, String> = emptyMap(),
    ): DbResult<JsonObject> {
        require(atMost > 0) { "atMost must be greater than zero" }
        return execute(
            entity.updateField,
            "mutation Update(${'$'}set: ${entity.typeName}UpdateInput!, " +
                "${'$'}filter: ${entity.typeName}Filter!, ${'$'}atMost: Int!) { " +
                "${entity.updateField}(set: ${'$'}set, filter: ${'$'}filter, atMost: ${'$'}atMost) " +
                "{ $selection } }",
            buildJsonObject {
                put("set", set)
                put("filter", filter)
                put("atMost", atMost)
            },
            headers,
        )
    }

    /** Deletes matching rows and returns the selected pg_graphql mutation payload. */
    suspend fun delete(
        entity: PgGraphqlEntity,
        filter: JsonObject,
        atMost: Int,
        selection: String = "affectedCount",
        headers: Map<String, String> = emptyMap(),
    ): JsonObject = deleteResult(entity, filter, atMost, selection, headers).strict(entity.deleteField)

    /** Deletes matching rows, preserving partial payload data and structured upstream errors. */
    suspend fun deleteResult(
        entity: PgGraphqlEntity,
        filter: JsonObject,
        atMost: Int,
        selection: String = "affectedCount",
        headers: Map<String, String> = emptyMap(),
    ): DbResult<JsonObject> {
        require(atMost > 0) { "atMost must be greater than zero" }
        return execute(
            entity.deleteField,
            "mutation Delete(${'$'}filter: ${entity.typeName}Filter!, ${'$'}atMost: Int!) { " +
                "${entity.deleteField}(filter: ${'$'}filter, atMost: ${'$'}atMost) { $selection } }",
            buildJsonObject {
                put("filter", filter)
                put("atMost", atMost)
            },
            headers,
        )
    }

    private suspend fun execute(
        responseKey: String,
        document: String,
        variables: JsonObject,
        headers: Map<String, String>,
    ): DbResult<JsonObject> = transport.executeResult(headers, GraphqlQuery(document, variables, responseKey))
}

internal fun viaduct.api.types.Input.toPgGraphqlInput(): JsonObject {
    @Suppress("UNCHECKED_CAST")
    val inputData =
        runCatching {
            javaClass.getMethod("getInputData").invoke(this) as Map<String, Any?>
        }.getOrElse {
            error("Viaduct input ${this::class.qualifiedName} does not expose its input data")
        }
    val inputType =
        runCatching {
            javaClass.getMethod("getGraphQLInputObjectType").invoke(this) as GraphQLInputObjectType
        }.getOrNull()
    val values =
        inputData.mapValues { (fieldName, value) ->
            val idType =
                inputType
                    ?.getFieldDefinition(fieldName)
                    ?.getAppliedDirective("idOf")
                    ?.getArgument("type")
                    ?.getValue<String>()
            if (idType != null && value is String) value.internalIdFor(idType) else value
        }
    return values.toPgGraphqlJsonElement() as JsonObject
}

private fun String.internalIdFor(typeName: String): String =
    runCatching {
        String(
            java.util.Base64
                .getDecoder()
                .decode(this),
        ).substringAfter("$typeName:", missingDelimiterValue = this)
    }.getOrDefault(this)

/** Names generated by pg_graphql for a table exposed as [typeName]. */
data class PgGraphqlEntity(
    val typeName: String,
) {
    init {
        require(Regex("[_A-Za-z][_0-9A-Za-z]*").matches(typeName)) {
            "typeName must be a GraphQL name"
        }
    }

    internal val insertField = "insertInto${typeName}Collection"
    internal val updateField = "update${typeName}Collection"
    internal val deleteField = "deleteFrom${typeName}Collection"
    val collectionField = typeName.replaceFirstChar(Char::lowercase) + "Collection"
}
