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
            "Persistent Node '$name' requires @resolver(isSelective: true) metadata. " +
                "Implement an @Resolver class extending NodeResolvers.$name to infer it, " +
                "or declare it explicitly in the application schema, " +
                "or set types.$name.excluded: true in pg-persistence.yaml. " +
                "PG Persistence infers metadata only for implemented node resolvers."
        }
    }
}
