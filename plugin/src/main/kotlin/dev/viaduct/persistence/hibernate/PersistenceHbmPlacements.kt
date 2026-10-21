package dev.viaduct.persistence.hibernate

import dev.viaduct.persistence.model.PersistenceModel
import dev.viaduct.persistence.model.PersistenceToManyAttribute
import dev.viaduct.persistence.model.PersistenceToManyStorage

/** A join table always uses its canonical owner's placement, including reciprocal/nested edges. */
internal class PersistenceHbmPlacements(
    model: PersistenceModel,
) {
    private val schemas = model.entities.associate { it.graphqlName to it.databaseSchema }
    private val associationOwners = model.associations.associate { it.typeName to it.ownerTypeName }
    private val joinOwners: Map<String, String>

    init {
        val owners =
            model.entities.flatMap { entity ->
                entity.attributes
                    .filterIsInstance<PersistenceToManyAttribute>()
                    .filter { it.storage == PersistenceToManyStorage.JOIN_TABLE_OWNER }
                    .map { requireNotNull(it.joinTableName) to entity.graphqlName }
            } +
                model.associations.flatMap { association ->
                    listOf(association.tableName to association.ownerTypeName) +
                        association.edgeMapping.attributes
                            .filterIsInstance<PersistenceToManyAttribute>()
                            .filter { it.storage == PersistenceToManyStorage.JOIN_TABLE_OWNER }
                            .map { requireNotNull(it.joinTableName) to association.typeName }
                }
        joinOwners =
            owners.groupBy({ it.first }, { it.second }).mapValues { (table, types) ->
                require(types.distinct().size == 1) { "Ambiguous canonical owner for join table $table: $types" }
                types.first()
            }
    }

    fun schema(typeName: String): String? {
        if (typeName in schemas) return schemas[typeName]
        return associationOwners[typeName]?.let(::schema)
    }

    fun joinSchema(tableName: String): String? =
        schema(
            requireNotNull(joinOwners[tableName]) {
                "No canonical owner for join table $tableName"
            },
        )
}
