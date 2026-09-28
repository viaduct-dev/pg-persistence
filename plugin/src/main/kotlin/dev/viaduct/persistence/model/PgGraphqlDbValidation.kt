package dev.viaduct.persistence.model

import viaduct.graphql.schema.ViaductSchema

fun validatePgGraphqlDbs(
    schema: ViaductSchema,
    persistentTypeNames: Set<String>,
) {
    val objectTypes =
        schema.types.values
            .filterIsInstance<ViaductSchema.Object>()
            .associateBy { it.name }

    for (rootName in persistentTypeNames) {
        val root = requireNotNull(objectTypes[rootName])
        validatePgGraphqlDb(
            type = root,
            objectTypes = objectTypes,
            path = listOf(root.name),
            visited = mutableSetOf(),
        )
    }
}

private fun validatePgGraphqlDb(
    type: ViaductSchema.Object,
    objectTypes: Map<String, ViaductSchema.Object>,
    path: List<String>,
    visited: MutableSet<String>,
) {
    if (!visited.add(type.name)) return

    type.fields.forEach { field ->
        if (isResolverOnly(field)) {
            val fieldPath = (path + field.name).joinToString(".")
            error(
                "Persistent Node '${path.first()}' transitively reaches '$fieldPath', which is " +
                    "annotated with @resolver. Persistent fields must be resolvable by pg_graphql.",
            )
        }
        val target = field.type.baseTypeDef as? ViaductSchema.Object
        objectTypes[target?.name]?.let {
            validatePgGraphqlDb(it, objectTypes, path + field.name, visited)
        }
    }
}

/**
 * A connection field may be resolver-backed when the resolver forwards its filter and
 * pagination arguments to the corresponding pg_graphql collection. The connection wrapper
 * remains structural and does not introduce another persisted entity or relationship.
 */
private fun isResolverBackedConnection(field: ViaductSchema.Field): Boolean =
    (field.type.baseTypeDef as? ViaductSchema.Object)
        ?.fields
        ?.singleOrNull { it.name == "edges" }
        ?.type
        ?.baseTypeDef
        ?.let { it as? ViaductSchema.Object }
        ?.fields
        ?.singleOrNull { it.name == "node" } != null

internal fun isResolverOnly(field: ViaductSchema.Field): Boolean =
    field.hasAppliedDirective("resolver") && !isResolverBackedConnection(field)
