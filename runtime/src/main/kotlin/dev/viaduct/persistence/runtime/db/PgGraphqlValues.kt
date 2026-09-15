package dev.viaduct.persistence.runtime.db

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A GraphQL input object encoded from ordinary Kotlin field values. */
class PgGraphqlObject private constructor(
    private val json: JsonObject,
) {
    internal fun encoded(): JsonObject = JsonObject(json.toMap())

    companion object {
        internal fun from(json: JsonObject): PgGraphqlObject = PgGraphqlObject(json)

        fun of(vararg fields: Pair<String, Any?>): PgGraphqlObject =
            PgGraphqlObject(
                buildJsonObject {
                    fields.forEach { (name, value) -> put(name, value.toPgGraphqlJsonElement()) }
                },
            )
    }
}

/** The pg_graphql values and filter for one update operation. */
data class PgGraphqlUpdate(
    val values: PgGraphqlObject,
    val filter: PgGraphqlFilter,
)

/** The pg_graphql filter for one delete operation. */
data class PgGraphqlDelete(
    val filter: PgGraphqlFilter,
)

/** A pg_graphql filter encoded without exposing JSON construction to applications. */
class PgGraphqlFilter private constructor(
    private val json: JsonObject,
) {
    internal fun encoded(): JsonObject = JsonObject(json.toMap())

    companion object {
        fun empty(): PgGraphqlFilter = PgGraphqlFilter(JsonObject(emptyMap()))

        fun eq(
            field: String,
            value: Any?,
        ): PgGraphqlFilter = comparison(field, "eq", value)

        fun neq(
            field: String,
            value: Any?,
        ): PgGraphqlFilter = comparison(field, "neq", value)

        fun lte(
            field: String,
            value: Any?,
        ): PgGraphqlFilter = comparison(field, "lte", value)

        fun ilike(
            field: String,
            pattern: String,
        ): PgGraphqlFilter = comparison(field, "ilike", pattern)

        fun isNull(field: String): PgGraphqlFilter = comparison(field, "is", "NULL")

        fun oneOf(
            field: String,
            values: Iterable<Any?>,
        ): PgGraphqlFilter =
            PgGraphqlFilter(
                buildJsonObject {
                    put(
                        field,
                        buildJsonObject {
                            put("in", buildJsonArray { values.forEach { add(it.toPgGraphqlJsonElement()) } })
                        },
                    )
                },
            )

        fun allOf(vararg filters: PgGraphqlFilter): PgGraphqlFilter =
            PgGraphqlFilter(
                buildJsonObject {
                    filters.forEach { filter -> filter.encoded().forEach { (field, value) -> put(field, value) } }
                },
            )

        fun anyOf(vararg filters: PgGraphqlFilter): PgGraphqlFilter =
            PgGraphqlFilter(
                buildJsonObject {
                    put("or", buildJsonArray { filters.forEach { add(it.encoded()) } })
                },
            )

        private fun comparison(
            field: String,
            operator: String,
            value: Any?,
        ): PgGraphqlFilter =
            PgGraphqlFilter(
                buildJsonObject { put(field, buildJsonObject { put(operator, value.toPgGraphqlJsonElement()) }) },
            )
    }
}

enum class PgGraphqlOrderDirection {
    ASC_NULLS_FIRST,
    ASC_NULLS_LAST,
    DESC_NULLS_FIRST,
    DESC_NULLS_LAST,
}

data class PgGraphqlOrder(
    val field: String,
    val direction: PgGraphqlOrderDirection,
) {
    internal fun toJson(): JsonObject =
        buildJsonObject {
            put(
                field,
                when (direction) {
                    PgGraphqlOrderDirection.ASC_NULLS_FIRST -> "AscNullsFirst"
                    PgGraphqlOrderDirection.ASC_NULLS_LAST -> "AscNullsLast"
                    PgGraphqlOrderDirection.DESC_NULLS_FIRST -> "DescNullsFirst"
                    PgGraphqlOrderDirection.DESC_NULLS_LAST -> "DescNullsLast"
                },
            )
        }
}

enum class PgGraphqlRecordNaming {
    GRAPHQL,
    SNAKE_CASE,
}

internal fun JsonElement.withRecordNaming(naming: PgGraphqlRecordNaming): JsonElement =
    when {
        naming == PgGraphqlRecordNaming.GRAPHQL -> this
        this is JsonObject ->
            JsonObject(
                entries.associate { (name, value) ->
                    val mappedName =
                        if (name == "uuidId") "_uuid_id" else name.replace(Regex("([A-Z])"), "_$1").lowercase()
                    mappedName to value.withRecordNaming(naming)
                },
            )
        this is JsonArray -> JsonArray(map { it.withRecordNaming(naming) })
        else -> this
    }
