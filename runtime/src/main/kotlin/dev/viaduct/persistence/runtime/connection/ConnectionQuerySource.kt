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
