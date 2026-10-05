package dev.viaduct.persistence.runtime.db

import dev.viaduct.persistence.runtime.connection.CursorProgress
import dev.viaduct.persistence.runtime.connection.PagingAccess
import dev.viaduct.persistence.runtime.graphql.GraphqlQuery
import dev.viaduct.persistence.runtime.graphql.PgGraphqlTransport
import io.ktor.client.HttpClient
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
    ): DbResult<JsonElement> {
        PagingAccess.validateOperation(document)
        return executeProvider(document, variables, responseKey, headers)
    }

    private suspend fun executeProvider(
        document: String,
        variables: JsonObject,
        responseKey: String,
        headers: Map<String, String>,
    ): DbResult<JsonElement> = transport.executeElementResult(headers, GraphqlQuery(document, variables, responseKey))

    /** Reads the complete unpaged collection; provider pagination remains internal. */
    suspend fun select(
        entity: PgGraphqlEntity,
        selection: String,
        filter: JsonObject = buildJsonObject {},
        orderBy: JsonArray = JsonArray(emptyList()),
        headers: Map<String, String> = emptyMap(),
    ): JsonArray {
        PagingAccess.validateOperation("{ records { $selection } }")
        val orderArgument = if (orderBy.isEmpty()) "" else ", orderBy: ${'$'}orderBy"
        val orderVariable = if (orderBy.isEmpty()) "" else ", ${'$'}orderBy: [${entity.typeName}OrderBy!]"
        val records = mutableListOf<JsonElement>()
        val progress = CursorProgress("Unpaged ${entity.collectionField}")
        var after: String? = null
        do {
            currentCoroutineContext().ensureActive()
            val root =
                executeProvider(
                    document =
                        "query Select(${'$'}filter: ${entity.typeName}Filter, ${'$'}after: Cursor$orderVariable) { " +
                            "${entity.collectionField}(filter: ${'$'}filter, after: ${'$'}after$orderArgument) { " +
                            "edges { node { $selection } } pageInfo { hasNextPage endCursor } } }",
                    variables =
                        buildJsonObject {
                            put("filter", filter)
                            after?.let { put("after", it) }
                            if (orderBy.isNotEmpty()) put("orderBy", orderBy)
                        },
                    responseKey = entity.collectionField,
                    headers = headers,
                ).strict(entity.collectionField).jsonObject
            val edges = root.getValue("edges").jsonArray
            records.addAll(edges.map { it.jsonObject.getValue("node") })
            val pageInfo = root.getValue("pageInfo").jsonObject
            if (!pageInfo.getValue("hasNextPage").jsonPrimitive.boolean) return JsonArray(records)
            check(edges.isNotEmpty()) { "Unpaged collection returned an empty page with hasNextPage" }
            after =
                checkNotNull(pageInfo["endCursor"]?.jsonPrimitive?.contentOrNull) {
                    "Unpaged collection has another page but no endCursor"
                }
            progress.record(after)
        } while (true)
    }

    /** Selects and deserializes records without exposing GraphQL payload JSON to the application. */
    suspend fun <T> selectRecords(
        entity: PgGraphqlEntity,
        selection: String,
        recordDeserializer: KSerializer<T>,
        filter: PgGraphqlFilter = PgGraphqlFilter.empty(),
        orderBy: List<PgGraphqlOrder> = emptyList(),
        naming: PgGraphqlRecordNaming = PgGraphqlRecordNaming.GRAPHQL,
        headers: Map<String, String> = emptyMap(),
    ): List<T> {
        val records =
            select(
                entity = entity,
                selection = selection,
                filter = filter.encoded(),
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
