package dev.viaduct.persistence.runtime.db

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Reads the pg_graphql `edges` object returned by filtered collection queries. */
internal object DbResponseReader {
    fun unwrapFirstNodePath(path: List<JsonElement>): List<JsonElement> {
        val wrapper = listOf(JsonPrimitive("edges"), JsonPrimitive(0), JsonPrimitive("node"))
        return if (path.size >= FILTERED_PATH_PREFIX_SIZE && path.drop(1).take(wrapper.size) == wrapper) {
            listOf(path.first()) + path.drop(FILTERED_PATH_PREFIX_SIZE)
        } else {
            path
        }
    }

    fun firstNode(
        data: JsonObject,
        responseKey: String,
    ): JsonObject =
        nodes(data).firstOrNull()
            ?: error("Db response for '$responseKey' matched no rows")

    fun firstNodeOrNull(data: JsonObject): JsonObject? = nodes(data).firstOrNull()

    fun nodes(data: JsonObject): List<JsonObject> =
        data["edges"]
            ?.jsonArray
            ?.mapNotNull { edge ->
                edge.jsonObject["node"]
                    ?.takeUnless { it is JsonNull }
                    ?.jsonObject
            }
            ?: error("Db response did not include 'edges' while reading a filtered collection")

    private const val FILTERED_PATH_PREFIX_SIZE = 4
}
