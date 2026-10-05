package dev.viaduct.persistence.runtime.db

import dev.viaduct.persistence.pggraphql.translation.PgGraphqlTranslation
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Reads the pg_graphql `edges` object returned by filtered collection queries. */
internal object DbResponseReader {
    fun restoreResult(
        result: DbResult<JsonObject>,
        root: DbRoot,
    ): DbResult<JsonObject> {
        val envelope = result.data?.let { PgGraphqlTranslation.restoreViaductResponseShape(it).jsonObject }
        val restoredErrors = result.errors.map { restoreErrorPath(it, root.responseKey) }
        val data = if (envelope != null && root.singleViaFilteredCollection) firstNodeOrNull(envelope) else envelope
        val errors =
            if (root.singleViaFilteredCollection) {
                restoredErrors.map { it.copy(path = unwrapFirstNodePath(it.path)) }
            } else {
                restoredErrors
            }
        return DbResult(data, errors)
    }

    private fun restoreErrorPath(
        error: UpstreamGraphqlError,
        responseKey: String,
    ): UpstreamGraphqlError {
        if (error.path.isEmpty()) return error
        val root = JsonPrimitive(responseKey)
        val hasRoot = error.path.first() == root
        val rawPath = if (hasRoot) error.path.drop(1) else error.path
        val restoredPath = PgGraphqlTranslation.restoreViaductResponsePath(rawPath)
        return error.copy(path = if (hasRoot) listOf(root) + restoredPath else restoredPath)
    }

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
