package dev.viaduct.persistence.runtime.db

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive

/** Executes an application-owned GraphQL operation with library-encoded variables. */
suspend fun PgGraphqlClient.execute(
    document: String,
    variables: PgGraphqlObject,
    responseKey: String,
    headers: Map<String, String> = emptyMap(),
): JsonElement = execute(document, variables.encoded(), responseKey, headers)

/** Decodes a SQL JSON/JSONB function result, which pg_graphql serializes as a JSON string. */
suspend fun <T> PgGraphqlClient.executeJson(
    document: String,
    responseKey: String,
    deserializer: KSerializer<T>,
    variables: PgGraphqlObject = PgGraphqlObject.of(),
    headers: Map<String, String> = emptyMap(),
): T {
    val value = requireNotNull(execute(document, variables, responseKey, headers))
    return functionJson.decodeFromString(deserializer, value.jsonPrimitive.content)
}

private val functionJson = Json { ignoreUnknownKeys = true }
