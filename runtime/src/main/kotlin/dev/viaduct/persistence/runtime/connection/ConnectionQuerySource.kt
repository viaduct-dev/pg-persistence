package dev.viaduct.persistence.runtime.connection

import dev.viaduct.persistence.runtime.graphql.GraphqlQuery
import kotlinx.serialization.json.JsonObject

/** Supplies provider pages to the shared Viaduct connection traversal. */
internal interface ConnectionQuerySource {
    fun query(
        paging: String,
        countOnly: Boolean = false,
    ): GraphqlQuery

    fun connection(response: JsonObject): JsonObject
}

internal fun mergeArguments(vararg groups: String): String =
    groups
        .map { it.trim().removePrefix("(").removeSuffix(")") }
        .filter(String::isNotBlank)
        .joinToString(", ")
        .takeIf(String::isNotBlank)
        ?.let { "($it)" }
        .orEmpty()
