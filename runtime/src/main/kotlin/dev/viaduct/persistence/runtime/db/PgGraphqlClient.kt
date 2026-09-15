package dev.viaduct.persistence.runtime.db

import dev.viaduct.persistence.runtime.graphql.GraphqlQuery
import dev.viaduct.persistence.runtime.graphql.PgGraphqlTransport
import io.ktor.client.HttpClient
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Executes arbitrary GraphQL operations against a pg_graphql endpoint. */
@Suppress("LongParameterList")
class PgGraphqlClient(
    httpClient: HttpClient,
    endpoint: String,
) {
    private val transport = PgGraphqlTransport(httpClient, endpoint, DbRequestHeaders { emptyMap() })
    private val json = Json { ignoreUnknownKeys = true }
    private val mutations = PgGraphqlMutationClient(httpClient, endpoint)

    /** Executes an arbitrary GraphQL operation and returns its root field. */
    suspend fun execute(
        document: String,
        variables: JsonObject = buildJsonObject {},
        responseKey: String,
        headers: Map<String, String> = emptyMap(),
    ): JsonElement = executeResult(document, variables, responseKey, headers).strict(responseKey)

    /** Returns the operation's data together with any GraphQL errors returned by pg_graphql. */
    suspend fun executeResult(
        document: String,
        variables: JsonObject = buildJsonObject {},
        responseKey: String,
        headers: Map<String, String> = emptyMap(),
    ): DbResult<JsonElement> = transport.executeElementResult(headers, GraphqlQuery(document, variables, responseKey))

    /** Queries a pg_graphql Relay collection and returns its node records. */
    suspend fun select(
        entity: PgGraphqlEntity,
        selection: String,
        filter: JsonObject = buildJsonObject {},
        first: Int = 10_000,
        orderBy: JsonArray = JsonArray(emptyList()),
        headers: Map<String, String> = emptyMap(),
    ): JsonArray {
        require(first > 0) { "first must be greater than zero" }
        val orderArgument = if (orderBy.isEmpty()) "" else ", orderBy: ${'$'}orderBy"
        val orderVariable = if (orderBy.isEmpty()) "" else ", ${'$'}orderBy: [${entity.typeName}OrderBy!]"
        val root =
            execute(
                document =
                    "query Select(${'$'}filter: ${entity.typeName}Filter, ${'$'}first: Int$orderVariable) { " +
                        "${entity.collectionField}(filter: ${'$'}filter, first: ${'$'}first$orderArgument) { " +
                        "edges { node { $selection } } } }",
                variables =
                    buildJsonObject {
                        put("filter", filter)
                        put("first", first)
                        if (orderBy.isNotEmpty()) put("orderBy", orderBy)
                    },
                responseKey = entity.collectionField,
                headers = headers,
            )
        return JsonArray(
            root.jsonObject
                .getValue("edges")
                .jsonArray
                .map { edge -> edge.jsonObject.getValue("node") },
        )
    }

    /** Selects and deserializes records without exposing GraphQL payload JSON to the application. */
    suspend fun <T> selectRecords(
        entity: PgGraphqlEntity,
        selection: String,
        recordDeserializer: KSerializer<T>,
        filter: PgGraphqlFilter = PgGraphqlFilter.empty(),
        first: Int = 10_000,
        orderBy: List<PgGraphqlOrder> = emptyList(),
        naming: PgGraphqlRecordNaming = PgGraphqlRecordNaming.GRAPHQL,
        headers: Map<String, String> = emptyMap(),
    ): List<T> {
        val records =
            select(
                entity = entity,
                selection = selection,
                filter = filter.encoded(),
                first = first,
                orderBy = JsonArray(orderBy.map(PgGraphqlOrder::toJson)),
                headers = headers,
            )
        return json.decodeFromJsonElement(ListSerializer(recordDeserializer), records.withRecordNaming(naming))
    }

    /** Inserts records and returns pg_graphql's affected-row count. */
    suspend fun insert(
        entity: PgGraphqlEntity,
        objects: List<PgGraphqlObject>,
        headers: Map<String, String> = emptyMap(),
    ): Int =
        mutations
            .insert(
                entity = entity,
                objects = JsonArray(objects.map(PgGraphqlObject::encoded)),
                headers = headers,
            ).getValue("affectedCount")
            .jsonPrimitive.content
            .toInt()

    /** Inserts and deserializes records without exposing mutation variable or payload JSON. */
    suspend fun <T> insertRecords(
        entity: PgGraphqlEntity,
        objects: List<PgGraphqlObject>,
        selection: String,
        recordDeserializer: KSerializer<T>,
        naming: PgGraphqlRecordNaming = PgGraphqlRecordNaming.GRAPHQL,
        headers: Map<String, String> = emptyMap(),
    ): List<T> {
        val payload =
            mutations.insert(
                entity = entity,
                objects = JsonArray(objects.map(PgGraphqlObject::encoded)),
                selection = "records { $selection }",
                headers = headers,
            )
        return decodeMutationRecords(payload, recordDeserializer, naming)
    }

    /** Updates and deserializes records without exposing mutation variable or payload JSON. */
    suspend fun <T> updateRecords(
        entity: PgGraphqlEntity,
        values: PgGraphqlObject,
        filter: PgGraphqlFilter,
        atMost: Int,
        selection: String,
        recordDeserializer: KSerializer<T>,
        naming: PgGraphqlRecordNaming = PgGraphqlRecordNaming.GRAPHQL,
        headers: Map<String, String> = emptyMap(),
    ): List<T> {
        val payload =
            mutations.update(
                entity = entity,
                set = values.encoded(),
                filter = filter.encoded(),
                atMost = atMost,
                selection = "records { $selection }",
                headers = headers,
            )
        return decodeMutationRecords(payload, recordDeserializer, naming)
    }

    /** Deletes records and returns pg_graphql's affected-row count. */
    suspend fun delete(
        entity: PgGraphqlEntity,
        filter: PgGraphqlFilter,
        atMost: Int,
        headers: Map<String, String> = emptyMap(),
    ): Int =
        mutations
            .delete(entity, filter.encoded(), atMost, headers = headers)
            .getValue("affectedCount")
            .jsonPrimitive.content
            .toInt()

    private fun <T> decodeMutationRecords(
        payload: JsonObject,
        recordDeserializer: KSerializer<T>,
        naming: PgGraphqlRecordNaming,
    ): List<T> =
        json.decodeFromJsonElement(
            ListSerializer(recordDeserializer),
            payload.getValue("records").jsonArray.withRecordNaming(naming),
        )
}
