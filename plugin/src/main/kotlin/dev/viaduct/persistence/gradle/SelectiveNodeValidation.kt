package dev.viaduct.persistence.gradle

import viaduct.graphql.schema.ViaductSchema

/** Checks application-owned resolver declarations without changing the schema. */
internal fun validateSelectiveNodeResolvers(
    schema: ViaductSchema,
    persistentTypeNames: Set<String>,
) {
    persistentTypeNames.sorted().forEach { name ->
        val type = schema.types.getValue(name)
        val resolver = type.appliedDirectives.singleOrNull { it.name == "resolver" }
        require(resolver?.arguments?.get("isSelective")?.value == true) {
            "Persistent Node '$name' requires an explicit @resolver(isSelective: true) declaration. " +
                "Add it to the application schema and implement its node resolver, " +
                "or exclude the type with denyList.types. PG Persistence does not generate resolver declarations."
        }
    }
}
