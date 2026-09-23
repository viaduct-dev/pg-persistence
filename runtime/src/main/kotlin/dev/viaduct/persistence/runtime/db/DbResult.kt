package dev.viaduct.persistence.runtime.db

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Partial GraphQL data accompanied by any upstream field errors. */
class DbResult<T>(
    val data: T?,
    errors: List<UpstreamGraphqlError> = emptyList(),
) {
    val errors: List<UpstreamGraphqlError> = java.util.List.copyOf(errors)

    fun copy(
        data: T? = this.data,
        errors: List<UpstreamGraphqlError> = this.errors,
    ): DbResult<T> = DbResult(data, errors)

    operator fun component1(): T? = data

    operator fun component2(): List<UpstreamGraphqlError> = errors

    override fun equals(other: Any?): Boolean = other is DbResult<*> && data == other.data && errors == other.errors

    override fun hashCode(): Int = 31 * (data?.hashCode() ?: 0) + errors.hashCode()

    override fun toString(): String = "DbResult(data=$data, errors=$errors)"
}

internal fun <T : Any> DbResult<T>.strict(responseKey: String): T {
    if (errors.isNotEmpty()) throw UpstreamGraphqlException(errors)
    return data ?: error("Db response did not include '$responseKey'")
}

/** An error returned by pg_graphql, preserving its response path and extensions. */
class UpstreamGraphqlError(
    val message: String,
    path: List<JsonElement> = emptyList(),
    locations: List<UpstreamGraphqlLocation> = emptyList(),
    extensions: JsonObject = JsonObject(emptyMap()),
) {
    val path: List<JsonElement> = java.util.List.copyOf(path.map(JsonElement::immutableSnapshot))
    val locations: List<UpstreamGraphqlLocation> = java.util.List.copyOf(locations)
    private val extensionValues = immutableJsonObjectValues(extensions)
    val extensions: JsonObject get() = JsonObject(extensionValues)

    fun copy(
        message: String = this.message,
        path: List<JsonElement> = this.path,
        locations: List<UpstreamGraphqlLocation> = this.locations,
        extensions: JsonObject = this.extensions,
    ): UpstreamGraphqlError = UpstreamGraphqlError(message, path, locations, extensions)

    operator fun component1(): String = message

    operator fun component2(): List<JsonElement> = path

    operator fun component3(): List<UpstreamGraphqlLocation> = locations

    operator fun component4(): JsonObject = extensions

    override fun equals(other: Any?): Boolean =
        other is UpstreamGraphqlError &&
            message == other.message &&
            path == other.path &&
            locations == other.locations &&
            extensions == other.extensions

    override fun hashCode(): Int {
        var result = message.hashCode()
        result = 31 * result + path.hashCode()
        result = 31 * result + locations.hashCode()
        return 31 * result + extensions.hashCode()
    }

    override fun toString(): String =
        "UpstreamGraphqlError(message=$message, path=$path, " +
            "locations=$locations, extensions=$extensions)"
}

data class UpstreamGraphqlLocation(
    val line: Int,
    val column: Int,
)

/** Exception thrown by DbClient methods without a `Result` suffix when pg_graphql returns errors. */
class UpstreamGraphqlException(
    errors: List<UpstreamGraphqlError>,
) : IllegalStateException(errors.joinToString(prefix = "Db fetch failed: ") { it.message }) {
    val errors: List<UpstreamGraphqlError> = java.util.List.copyOf(errors)
}
