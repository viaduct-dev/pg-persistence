package dev.viaduct.persistence.runtime.db

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Partial GraphQL data accompanied by any upstream field errors. */
data class DbResult<T>(
    val data: T?,
    val errors: List<UpstreamGraphqlError> = emptyList(),
)

internal fun <T : Any> DbResult<T>.strict(responseKey: String): T {
    if (errors.isNotEmpty()) throw UpstreamGraphqlException(errors)
    return data ?: error("Db response did not include '$responseKey'")
}

/** An error returned by pg_graphql, preserving its response path and extensions. */
data class UpstreamGraphqlError(
    val message: String,
    val path: List<JsonElement> = emptyList(),
    val locations: List<UpstreamGraphqlLocation> = emptyList(),
    val extensions: JsonObject = JsonObject(emptyMap()),
)

data class UpstreamGraphqlLocation(
    val line: Int,
    val column: Int,
)

/** Exception thrown by DbClient methods without a `Result` suffix when pg_graphql returns errors. */
class UpstreamGraphqlException(
    val errors: List<UpstreamGraphqlError>,
) : IllegalStateException(errors.joinToString(prefix = "Db fetch failed: ") { it.message })
