package dev.viaduct.persistence.model

import viaduct.graphql.schema.ViaductSchema

internal class PersistenceModelContext(
    includedObjects: Map<String, ViaductSchema.Object>,
    schemaTypes: Map<String, ViaductSchema.TypeDef> = includedObjects,
    private val policy: PersistenceModelPolicy = PersistenceModelPolicy(),
    private val relationshipTargetResolver: RelationshipTargetResolver = RelationshipTargetResolver(),
    private val collectionMappingResolver: CollectionMappingResolver = CollectionMappingResolver(),
) {
    val includedObjects: Map<String, ViaductSchema.Object> =
        java.util.Collections.unmodifiableMap(LinkedHashMap(includedObjects))
    val schemaTypes: Map<String, ViaductSchema.TypeDef> =
        java.util.Collections.unmodifiableMap(LinkedHashMap(schemaTypes))
    private val enums = linkedMapOf<String, PersistenceEnum>()
    private val entities = linkedMapOf<String, PersistenceEntity>()
    val generatedEnums: List<PersistenceEnum> get() = java.util.List.copyOf(enums.values)
    val generatedEntities: List<PersistenceEntity> get() = java.util.List.copyOf(entities.values)
    private val relationshipCache =
        mutableMapOf<ViaductSchema.Object, Map<ViaductSchema.Field, PersistenceRelationship?>>()
    private val edgeMappingFactory = PersistenceEdgeMappingFactory()
    private val edgeMappings = linkedMapOf<String, PersistenceEdgeMapping?>()
    private val buildingEdgeMappings = mutableSetOf<String>()
    val unidirectionalTargetForeignKeyFields: Set<String>
        get() = policy.unidirectionalTargetForeignKeyFields

    fun register(entity: PersistenceEntity) {
        entities[entity.graphqlName] = entity
    }

    fun register(enumType: PersistenceEnum) {
        enums.putIfAbsent(enumType.graphqlName, enumType)
    }

    fun isSemanticallyNonNull(
        type: ViaductSchema.Object,
        field: ViaductSchema.Field,
        relationship: PersistenceRelationship? = relationships(type)[field],
    ): Boolean =
        !field.hasAppliedDirective("resolver") &&
            relationship?.collection != true &&
            policy.requiresNonNull(type, field)

    fun validateSemanticNotNullCoordinates() {
        val coordinatePattern = Regex("[A-Za-z_][A-Za-z0-9_]*\\.[A-Za-z_][A-Za-z0-9_]*")
        policy.semanticNotNullTypeNames.forEach { typeName ->
            require(typeName in includedObjects) {
                "semanticNotNull.types contains '$typeName', which is not a persistent object type"
            }
        }
        policy.semanticNotNullFieldCoordinates.forEach { coordinate ->
            require(coordinatePattern.matches(coordinate)) {
                "semanticNotNull.fields contains malformed field coordinate '$coordinate'"
            }
            val (typeName, fieldName) = coordinate.split('.', limit = 2)
            val type = includedObjects[typeName]
            requireNotNull(type) {
                "semanticNotNull.fields contains '$coordinate', but '$typeName' is not persistent"
            }
            val field = type.fields.singleOrNull { it.name == fieldName }
            requireNotNull(field) { "semanticNotNull.fields contains unknown field '$coordinate'" }
            require(!field.hasAppliedDirective("resolver")) {
                "semanticNotNull.fields contains resolver-only field '$coordinate'"
            }
            require(
                relationships(type)[field]?.collection != true,
            ) {
                "semanticNotNull.fields contains '$coordinate', but it is a to-many relationship"
            }
        }
    }

    fun semanticNotNullCoordinates(): Set<String> =
        buildSet {
            addAll(policy.semanticNotNullFieldCoordinates)
            policy.semanticNotNullTypeNames.forEach { typeName ->
                includedObjects
                    .getValue(typeName)
                    .fields
                    .filter { field -> isSemanticallyNonNull(includedObjects.getValue(typeName), field) }
                    .mapTo(this) { field -> "$typeName.${field.name}" }
            }
        }

    fun relationships(type: ViaductSchema.Object): Map<ViaductSchema.Field, PersistenceRelationship?> =
        relationshipCache.getOrPut(type) {
            type.fields.associateWith { field ->
                relationshipTargetResolver.resolve(type, field, includedObjects, schemaTypes)?.let {
                    it.copy(nullable = it.nullable && !isSemanticallyNonNull(type, field, it))
                }
            }
        }

    fun edgeMapping(edgeTypeName: String?): PersistenceEdgeMapping? =
        edgeTypeName?.let { name ->
            if (name in edgeMappings) {
                edgeMappings.getValue(name)
            } else {
                buildEdgeMapping(name).also { edgeMappings[name] = it }
            }
        }

    private fun buildEdgeMapping(name: String): PersistenceEdgeMapping? {
        require(buildingEdgeMappings.add(name)) {
            "Recursive connection edge mapping cannot be persisted for '$name'"
        }
        return try {
            (schemaTypes[name] as? ViaductSchema.Object)?.let { edgeMappingFactory.build(it, this) }
        } finally {
            buildingEdgeMappings.remove(name)
        }
    }

    fun collectionMapping(
        source: ViaductSchema.Object,
        sourceField: ViaductSchema.Field,
        target: ViaductSchema.Object,
        hasPersistedEdgeFields: Boolean = false,
    ): PersistenceCollectionMapping =
        collectionMappingResolver.resolve(
            CollectionMappingContext(
                source = source,
                sourceField = sourceField,
                target = target,
                sourceCollections = relatedFields(source, target.name, collection = true),
                inverseToOneFields = inverseToOneFields(source, sourceField, target),
                inverseCollections = relatedFields(target, source.name, collection = true),
                unidirectionalTargetForeignKeyFields = policy.unidirectionalTargetForeignKeyFields,
                hasPersistedEdgeFields = hasPersistedEdgeFields,
                sourceEdgeMappings = edgeMappings(source),
                inverseEdgeMappings = edgeMappings(target),
            ),
        )

    /**
     * The to-one fields on [target] that could be the inverse of [source].[sourceField]. When
     * [target] has more than one to-one field targeting [source] — an inherently ambiguous
     * pairing, since a declared reverse collection alone can't say which one it's the inverse of
     * — [inverseFieldOverrides] lets the caller name the specific field explicitly.
     */
    private fun inverseToOneFields(
        source: ViaductSchema.Object,
        sourceField: ViaductSchema.Field,
        target: ViaductSchema.Object,
    ): List<ViaductSchema.Field> {
        val candidates = relatedFields(target, source.name, collection = false)
        val overrideFieldName = policy.inverseFieldOverrides["${source.name}.${sourceField.name}"] ?: return candidates
        val matched = candidates.singleOrNull { it.name == overrideFieldName }
        requireNotNull(matched) {
            "inverseFieldOverrides[\"${source.name}.${sourceField.name}\"] = \"$overrideFieldName\" does not " +
                "match any to-one field on ${target.name} targeting ${source.name}: " +
                candidates.joinToString { it.name }
        }
        return listOf(matched)
    }
}

private fun PersistenceModelContext.edgeMappings(type: ViaductSchema.Object): Map<String, PersistenceEdgeMapping?> {
    val relationships = relationships(type)
    return type.fields.associate { field ->
        // Abstract collections own independent rows and cannot share a concrete inverse mapping.
        field.name to edgeMapping(relationships.getValue(field)?.takeUnless { it.isAbstract }?.edgeTypeName)
    }
}

private fun PersistenceModelContext.relatedFields(
    type: ViaductSchema.Object,
    targetName: String,
    collection: Boolean,
): List<ViaductSchema.Field> =
    relationships(type)
        .filter { (_, relationship) ->
            relationship?.let {
                it.targetName == targetName && it.collection == collection
            } == true
        }.keys
        .toList()

private fun PersistenceModelPolicy.requiresNonNull(
    type: ViaductSchema.Object,
    field: ViaductSchema.Field,
): Boolean = type.name in semanticNotNullTypeNames || "${type.name}.${field.name}" in semanticNotNullFieldCoordinates
