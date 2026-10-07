package dev.viaduct.persistence.model

/** Adds schema-adjacent uniqueness policy to the generated persistence model. */
internal fun PersistenceModel.withUniqueConstraints(keysByType: Map<String, List<List<String>>>): PersistenceModel {
    val entitiesWithKeys =
        entities.map { entity ->
            val keys = keysByType[entity.graphqlName].orEmpty()
            keys.forEach { fields -> validateUniqueKey(entity, fields) }
            PersistenceEntity(
                entity.graphqlName,
                entity.generatedGlobalId,
                entity.attributes,
                keys.map { it.sorted() }.distinct().sortedBy { it.joinToString(",") },
            )
        }
    return PersistenceModel(entitiesWithKeys, enums, semanticNotNullCoordinates, abstractTypes, retryableTransactions)
}

private fun validateUniqueKey(
    entity: PersistenceEntity,
    fields: List<String>,
) {
    val key = "types.${entity.graphqlName}.unique"
    require(fields.isNotEmpty() && fields.all { it.isNotBlank() } && fields.distinct().size == fields.size) {
        "$key requires distinct, non-blank fields in each key"
    }
    fields.forEach { name ->
        val attribute = entity.attributes.singleOrNull { it.name == name }
        require(
            attribute is PersistenceToOneAttribute ||
                attribute is PersistenceBasicAttribute &&
                !attribute.collection &&
                name != "id" &&
                name != "internalId",
        ) {
            "$key field '$name' must be a stored scalar or to-one relationship"
        }
    }
}
