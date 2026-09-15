package dev.viaduct.persistence.model

import viaduct.graphql.schema.ViaductSchema

internal class PersistenceEntityAttributeFactory(
    private val modelValidator: PersistenceModelValidator,
) {
    fun build(
        type: ViaductSchema.Object,
        generatedGlobalId: Boolean,
        modelContext: PersistenceModelContext,
    ): PersistenceEntity {
        val relationships = modelContext.relationships(type)
        modelValidator.validateNoConflictingScalarRelationshipIds(type, relationships)

        return PersistenceEntity(
            graphqlName = type.name,
            generatedGlobalId = generatedGlobalId,
            attributes =
                buildAttributes(
                    type = type,
                    generatedGlobalId = generatedGlobalId,
                    relationships = relationships,
                    modelContext = modelContext,
                ),
        )
    }

    private fun buildAttributes(
        type: ViaductSchema.Object,
        generatedGlobalId: Boolean,
        relationships: Map<ViaductSchema.Field, PersistenceRelationshipTarget?>,
        modelContext: PersistenceModelContext,
    ): List<PersistenceAttribute> =
        buildList {
            if (generatedGlobalId) {
                add(
                    PersistenceBasicAttribute(
                        name = "internalId",
                        nullable = false,
                        kotlinType = "java.util.UUID",
                    ),
                )
            }
            val strategies = attributeStrategies(generatedGlobalId)
            addAll(
                type.fields.mapNotNull { field ->
                    if (field.isIdOfAliasForObjectRelationship(relationships)) return@mapNotNull null
                    val attribute =
                        buildAttribute(
                            type = type,
                            field = field,
                            relationship = relationships.getValue(field),
                            modelContext = modelContext,
                            strategies = strategies,
                        )
                    attribute.withIdOfAliasNullability(type, relationships, modelContext)
                },
            )
        }

    private fun ViaductSchema.Field.isIdOfAliasForObjectRelationship(
        relationships: Map<ViaductSchema.Field, PersistenceRelationshipTarget?>,
    ): Boolean {
        val relationship = relationships[this]
        val objectFieldName = name.removeSuffix("Id")
        return relationship != null &&
            relationship.idOfDirected &&
            !relationship.collection &&
            name.endsWith("Id") &&
            relationships.any { (field, candidate) ->
                field.name == objectFieldName &&
                    candidate != null &&
                    !candidate.collection &&
                    !candidate.idOfDirected &&
                    candidate.targetName == relationship.targetName
            }
    }

    private fun PersistenceAttribute?.withIdOfAliasNullability(
        type: ViaductSchema.Object,
        relationships: Map<ViaductSchema.Field, PersistenceRelationshipTarget?>,
        modelContext: PersistenceModelContext,
    ): PersistenceAttribute? =
        when {
            this !is PersistenceToOneAttribute || idOfDirected -> this
            else -> {
                val alias =
                    relationships.entries
                        .singleOrNull { (field, relationship) ->
                            field.name == "${name}Id" &&
                                relationship?.idOfDirected == true &&
                                relationship.targetName == targetTypeName
                        }?.key
                alias?.let {
                    val aliasNullable = it.type.isNullable && !modelContext.isSemanticallyNonNull(type, it)
                    copy(nullable = nullable && aliasNullable)
                } ?: this
            }
        }

    private fun buildAttribute(
        type: ViaductSchema.Object,
        field: ViaductSchema.Field,
        relationship: PersistenceRelationshipTarget?,
        modelContext: PersistenceModelContext,
        strategies: List<PersistenceAttributeStrategy>,
    ): PersistenceAttribute? {
        val context =
            PersistenceAttributeContext(
                source = type,
                field = field,
                relationship = relationship,
                modelContext = modelContext,
            )
        return strategies.firstNotNullOf { it.tryBuild(context) }.attribute
    }

    private fun attributeStrategies(generatedGlobalId: Boolean): List<PersistenceAttributeStrategy> =
        listOf(
            ToManyAttributeStrategy(),
            ToOneAttributeStrategy(),
            ResolverAttributeStrategy(),
            GraphqlIdAttributeStrategy(generatedGlobalId),
            BasicAttributeStrategy(),
        )
}
