package dev.viaduct.persistence.model

import viaduct.graphql.schema.ViaductSchema

/** Resolves the declared target and collection shape once for every stored field. */
internal class RelationshipTargetResolver {
    fun resolve(
        owner: ViaductSchema.Object,
        field: ViaductSchema.Field,
        includedObjects: Map<String, ViaductSchema.Object>,
        schemaTypes: Map<String, ViaductSchema.TypeDef> = includedObjects,
    ): PersistenceRelationship? {
        if (isResolverOnly(field)) return null
        val declared = field.type.baseTypeDef
        val direct = declared.isAbstract || declared.name in includedObjects
        val edge = if (direct) null else declared.connectionEdge()
        val node = edge?.fieldType("node")
        val nodes = if (direct || node != null) null else (declared as? ViaductSchema.Object)?.fieldType("nodes")
        val idTarget = field.idOfTarget(owner, schemaTypes)
        val target =
            if (direct) {
                declared
            } else {
                node ?: (nodes as? ViaductSchema.Object) ?: idTarget
                    ?: (declared as? ViaductSchema.Object)
            }
        return target
            ?.takeIf {
                it is ViaductSchema.Object || it.isAbstract
            }?.let {
                PersistenceRelationship(
                    ownerType = owner.name,
                    fieldName = field.name,
                    declaredType = it,
                    collection = field.type.isList || node != null || nodes is ViaductSchema.Object,
                    nullable = field.type.isNullable,
                    edgeTypeName = edge?.name,
                    connectionTypeName = declared.name.takeIf { node != null },
                    idOfDirected = idTarget != null,
                )
            }
    }
}

private fun ViaductSchema.Field.idOfTarget(
    owner: ViaductSchema.Object,
    types: Map<String, ViaductSchema.TypeDef>,
): ViaductSchema.Object? {
    if (type.baseTypeDef.name != "ID") return null
    val target = idOfTypeName()?.let(types::get)
    require(target?.isAbstract != true) {
        "Persisted field ${owner.name}.$name uses @idOf with abstract type ${target?.name}. " +
            "Declare the union/interface relationship directly and use withReference with a concrete typed ID."
    }
    return if (type.isList) null else target as? ViaductSchema.Object
}

private fun ViaductSchema.Field.idOfTypeName(): String? =
    appliedDirectives
        .firstOrNull { it.name == "idOf" }
        ?.arguments
        ?.get("type")
        ?.let { it as? ViaductSchema.StringLiteral }
        ?.value

internal fun ViaductSchema.Object.fieldType(name: String): ViaductSchema.TypeDef? =
    fields.singleOrNull { it.name == name }?.type?.baseTypeDef

internal fun ViaductSchema.TypeDef.connectionEdge(): ViaductSchema.Object? =
    (this as? ViaductSchema.Object)?.fieldType("edges") as? ViaductSchema.Object

internal data class PersistenceCollectionMapping(
    val inverseFieldName: String?,
    val storage: PersistenceToManyStorage,
    val joinTableName: String? = null,
)

internal data class CollectionMappingContext(
    val source: ViaductSchema.Object,
    val sourceField: ViaductSchema.Field,
    val target: ViaductSchema.Object,
    val sourceCollections: List<ViaductSchema.Field>,
    val inverseToOneFields: List<ViaductSchema.Field>,
    val inverseCollections: List<ViaductSchema.Field>,
    val unidirectionalTargetForeignKeyFields: Set<String>,
    val hasPersistedEdgeFields: Boolean = false,
    val sourceEdgeMappings: Map<String, PersistenceEdgeMapping?> = emptyMap(),
    val inverseEdgeMappings: Map<String, PersistenceEdgeMapping?> = emptyMap(),
)

internal interface CollectionMappingStrategy {
    fun resolve(context: CollectionMappingContext): PersistenceCollectionMapping?
}

internal class CollectionMappingResolver(
    strategies: List<CollectionMappingStrategy> =
        listOf(
            EdgeCollectionMappingStrategy(),
            InverseToOneCollectionMappingStrategy(),
            MutualCollectionMappingStrategy(),
            AmbiguousInverseCollectionStrategy(),
            ConfiguredTargetForeignKeyStrategy(),
            SingleUnidirectionalCollectionStrategy(),
            MultipleUnidirectionalCollectionStrategy(),
            FallbackJoinTableStrategy(),
        ),
) {
    private val strategies = java.util.List.copyOf(strategies)

    fun resolve(context: CollectionMappingContext): PersistenceCollectionMapping =
        checkNotNull(strategies.firstNotNullOfOrNull { it.resolve(context) }) {
            "No collection mapping strategy matched ${context.source.name}.${context.sourceField.name}"
        }
}

private class InverseToOneCollectionMappingStrategy : CollectionMappingStrategy {
    override fun resolve(context: CollectionMappingContext): PersistenceCollectionMapping? {
        if (context.hasPersistedEdgeFields) return null
        require(context.inverseToOneFields.size <= 1) {
            val coordinate = "${context.source.name}.${context.sourceField.name}"
            val candidates = context.inverseToOneFields.joinToString { it.name }
            "Relationship $coordinate -> ${context.target.name} is ambiguous because " +
                "${context.target.name} has multiple references back to ${context.source.name}: " +
                "$candidates. Specify which one $coordinate is the inverse of by adding " +
                "an inverseFieldOverrides entry to persistence.yaml " +
                "(viaductPgPersistence.persistenceConfigFile), e.g.:\n" +
                "relationships:\n  inverseFieldOverrides:\n    $coordinate: <fieldName>\n" +
                "using one of: $candidates."
        }
        return context.inverseToOneFields.singleOrNull()?.let { inverse ->
            require(context.sourceCollections.size == 1) {
                "Relationship ${context.source.name} -> ${context.target.name} is ambiguous because " +
                    "multiple collections would share ${context.target.name}.${inverse.name}: " +
                    context.sourceCollections.joinToString { it.name }
            }
            PersistenceCollectionMapping(
                inverseFieldName = inverse.name,
                storage = PersistenceToManyStorage.TARGET_FOREIGN_KEY,
            )
        }
    }
}

private class EdgeCollectionMappingStrategy : CollectionMappingStrategy {
    override fun resolve(context: CollectionMappingContext): PersistenceCollectionMapping? {
        val sourceHasEdgeFields =
            context.hasPersistedEdgeFields || context.sourceEdgeMappings[context.sourceField.name] != null
        val inverseEdgeFields =
            context.inverseCollections.filter { context.inverseEdgeMappings[it.name] != null }
        return when {
            !sourceHasEdgeFields && inverseEdgeFields.isEmpty() -> null
            sourceHasEdgeFields ->
                PersistenceCollectionMapping(
                    inverseFieldName = null,
                    storage = PersistenceToManyStorage.JOIN_TABLE_OWNER,
                    joinTableName = associationJoinTableName(context.source.name, context.sourceField.name),
                )
            else -> {
                require(inverseEdgeFields.size == 1) {
                    "Relationship ${context.source.name}.${context.sourceField.name} is ambiguous because " +
                        "${context.target.name} has multiple association-backed collections back: " +
                        inverseEdgeFields.joinToString { it.name }
                }
                val inverse = inverseEdgeFields.single()
                PersistenceCollectionMapping(
                    inverseFieldName = inverse.name,
                    storage = PersistenceToManyStorage.JOIN_TABLE_INVERSE,
                    joinTableName = associationJoinTableName(context.target.name, inverse.name),
                )
            }
        }
    }
}

private class MutualCollectionMappingStrategy : CollectionMappingStrategy {
    override fun resolve(context: CollectionMappingContext): PersistenceCollectionMapping? {
        val hasEdgeFields =
            context.sourceEdgeMappings.values.any { it != null } ||
                context.inverseEdgeMappings.values.any { it != null }
        return when {
            hasEdgeFields ||
                context.sourceCollections.size != 1 ||
                context.inverseCollections.size != 1 -> null
            else -> {
                val inverseField = context.inverseCollections.single()
                val sourceKey = "${context.source.name}.${context.sourceField.name}"
                val targetKey = "${context.target.name}.${inverseField.name}"
                val sourceOwns = sourceKey <= targetKey
                val ownerType = if (sourceOwns) context.source else context.target
                val ownerField = if (sourceOwns) context.sourceField else inverseField
                PersistenceCollectionMapping(
                    inverseFieldName = if (sourceOwns) null else inverseField.name,
                    storage =
                        if (sourceOwns) {
                            PersistenceToManyStorage.JOIN_TABLE_OWNER
                        } else {
                            PersistenceToManyStorage.JOIN_TABLE_INVERSE
                        },
                    joinTableName = associationJoinTableName(ownerType.name, ownerField.name),
                )
            }
        }
    }
}

private class AmbiguousInverseCollectionStrategy : CollectionMappingStrategy {
    override fun resolve(context: CollectionMappingContext): PersistenceCollectionMapping? {
        require(context.inverseCollections.isEmpty()) {
            "Relationship ${context.source.name}.${context.sourceField.name} -> " +
                "${context.target.name} is ambiguous because ${context.target.name} has " +
                "multiple collection references back: " +
                context.inverseCollections.joinToString { it.name }
        }
        return null
    }
}

private class ConfiguredTargetForeignKeyStrategy : CollectionMappingStrategy {
    override fun resolve(context: CollectionMappingContext): PersistenceCollectionMapping? {
        val sourceKey = "${context.source.name}.${context.sourceField.name}"
        if (sourceKey !in context.unidirectionalTargetForeignKeyFields) return null
        require(!context.hasPersistedEdgeFields) {
            "Configured target-foreign-key relationship $sourceKey cannot persist custom edge fields; " +
                "remove the configuration so an association table can be used"
        }
        require(context.sourceCollections.size == 1) {
            "Configured target-foreign-key relationship $sourceKey must be the only collection " +
                "from ${context.source.name} to ${context.target.name}"
        }
        return PersistenceCollectionMapping(
            inverseFieldName = null,
            storage = PersistenceToManyStorage.TARGET_FOREIGN_KEY,
        )
    }
}

private class SingleUnidirectionalCollectionStrategy : CollectionMappingStrategy {
    override fun resolve(context: CollectionMappingContext): PersistenceCollectionMapping? =
        if (context.sourceCollections.size == 1 && !context.hasPersistedEdgeFields) {
            PersistenceCollectionMapping(
                inverseFieldName = null,
                storage = PersistenceToManyStorage.TARGET_FOREIGN_KEY,
            )
        } else if (context.sourceCollections.size == 1) {
            PersistenceCollectionMapping(
                inverseFieldName = null,
                storage = PersistenceToManyStorage.JOIN_TABLE_OWNER,
                joinTableName = associationJoinTableName(context.source.name, context.sourceField.name),
            )
        } else {
            null
        }
}

private class MultipleUnidirectionalCollectionStrategy : CollectionMappingStrategy {
    override fun resolve(context: CollectionMappingContext): PersistenceCollectionMapping? =
        if (context.sourceCollections.size > 1) {
            PersistenceCollectionMapping(
                inverseFieldName = null,
                storage = PersistenceToManyStorage.JOIN_TABLE_OWNER,
                joinTableName =
                    associationJoinTableName(
                        context.source.name,
                        context.sourceField.name,
                    ),
            )
        } else {
            null
        }
}

private class FallbackJoinTableStrategy : CollectionMappingStrategy {
    override fun resolve(context: CollectionMappingContext): PersistenceCollectionMapping =
        PersistenceCollectionMapping(
            inverseFieldName = null,
            storage = PersistenceToManyStorage.JOIN_TABLE_OWNER,
            joinTableName =
                associationJoinTableName(
                    context.source.name,
                    context.sourceField.name,
                ),
        )
}
