package dev.viaduct.persistence.model

/** Index field order is significant: [a, b] and [b, a] serve different leading-key searches. */
internal fun PersistenceModel.withIndexes(indexesByType: Map<String, List<List<String>>>): PersistenceModel =
    PersistenceModel(
        entities.map { entity ->
            val indexes = indexesByType[entity.graphqlName].orEmpty()
            indexes.forEach { validateStoredKey(entity, it, "indexes") }
            PersistenceEntity(
                entity.graphqlName,
                entity.generatedGlobalId,
                entity.attributes,
                entity.uniqueKeys,
                indexes.distinct().sortedBy { it.joinToString(",") },
                entity.databaseSchema,
                entity.owningModule,
            )
        },
        enums,
        semanticNotNullCoordinates,
        abstractTypes,
        retryableTransactions,
    )
