package dev.viaduct.persistence.runtime.db

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/** Describes the pg_graphql root field used for one db read. */
class DbRoot(
    val field: String,
    val arguments: String = "",
    val variableDefinitions: String = "",
    variables: JsonObject = buildJsonObject {},
    val responseKey: String = field,
    val singleViaFilteredCollection: Boolean = false,
) {
    private val variableValues: Map<String, JsonElement> =
        java.util.Collections.unmodifiableMap(java.util.LinkedHashMap(variables))

    val variables: JsonObject
        get() = JsonObject(variableValues)
}

data class DbRead(
    val root: DbRoot,
    /** Concrete table type to use when the root selection is an interface or union. */
    val concreteType: viaduct.api.reflect.Type<out viaduct.api.types.CompositeOutput>? = null,
)
