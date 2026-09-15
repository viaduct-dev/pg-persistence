package dev.viaduct.persistence.runtime.db

import graphql.schema.GraphQLInputObjectType
import kotlinx.serialization.json.JsonObject
import viaduct.api.types.Input
import viaduct.api.types.NodeObject

/** Explicitly converts a Viaduct input into values accepted by pg_graphql insert operations. */
fun Input.toPgGraphqlInsert(): PgGraphqlObject = PgGraphqlObject.from(toPgGraphqlInput())

/** Converts a Viaduct input into a single-row pg_graphql update. */
inline fun <reified T : NodeObject> Input.toPgGraphqlUpdate(identifierField: String? = null): PgGraphqlUpdate =
    toPgGraphqlUpdate(reflectedType(T::class.java).name, identifierField)

/** Converts a Viaduct input into a single-row pg_graphql delete. */
inline fun <reified T : NodeObject> Input.toPgGraphqlDelete(identifierField: String? = null): PgGraphqlDelete =
    toPgGraphqlDelete(reflectedType(T::class.java).name, identifierField)

@PublishedApi
internal fun Input.toPgGraphqlUpdate(
    entityName: String,
    identifierField: String? = null,
): PgGraphqlUpdate {
    val values = toPgGraphqlInput()
    val identifierName = identifierName(entityName, identifierField)
    require(values.containsKey(identifierName)) {
        "Mutation input does not contain identifier field '$identifierName'"
    }
    return PgGraphqlUpdate(
        PgGraphqlObject.from(JsonObject(values.filterKeys { it != identifierName })),
        identifierFilter(values, identifierName),
    )
}

@PublishedApi
internal fun Input.toPgGraphqlDelete(
    entityName: String,
    identifierField: String? = null,
): PgGraphqlDelete {
    val values = toPgGraphqlInput()
    val identifierName = identifierName(entityName, identifierField)
    return PgGraphqlDelete(identifierFilter(values, identifierName))
}

private fun identifierFilter(
    values: JsonObject,
    identifierName: String,
): PgGraphqlFilter {
    val identifier =
        requireNotNull(values[identifierName]) {
            "Mutation input does not contain identifier field '$identifierName'"
        }
    return PgGraphqlFilter.eq("uuidId", identifier)
}

private fun Input.identifierName(
    entityName: String,
    explicitField: String?,
): String {
    val candidates =
        buildSet {
            graphQLInputType().fieldDefinitions.forEach { field ->
                if (field.getAppliedDirective("idOf")?.getArgument("type")?.getValue<String>() == entityName) {
                    add(field.name)
                }
            }
        }
    if (explicitField != null) {
        require(explicitField in candidates) {
            "Identifier field '$explicitField' must have @idOf(type: \"$entityName\")"
        }
        return explicitField
    }
    require(candidates.isNotEmpty()) { "Mutation input has no @idOf field for $entityName" }
    require(candidates.size == 1) {
        "Mutation input has multiple @idOf fields for $entityName: ${candidates.joinToString()}. " +
            "Pass identifierField explicitly."
    }
    return candidates.single()
}

private fun Input.graphQLInputType(): GraphQLInputObjectType =
    javaClass.getMethod("getGraphQLInputObjectType").invoke(this) as GraphQLInputObjectType
