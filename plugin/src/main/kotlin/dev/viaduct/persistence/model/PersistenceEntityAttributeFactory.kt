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

        return buildPersistenceEntity(
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
        relationships: Map<ViaductSchema.Field, PersistenceRelationship?>,
        modelContext: PersistenceModelContext,
    ): List<PersistenceAttribute> {
        val fieldFactory = PersistenceFieldAttributeFactory(generatedGlobalId)
        return type.fields.flatMap { field ->
            if (field.isIdOfAliasForObjectRelationship(relationships)) return@flatMap emptyList()
            val attributes = fieldFactory.build(type, field, modelContext)
            attributes.map { it.withIdOfAliasNullability(type, relationships, modelContext) }
        }
    }

    private fun ViaductSchema.Field.isIdOfAliasForObjectRelationship(
        relationships: Map<ViaductSchema.Field, PersistenceRelationship?>,
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

    private fun PersistenceAttribute.withIdOfAliasNullability(
        type: ViaductSchema.Object,
        relationships: Map<ViaductSchema.Field, PersistenceRelationship?>,
        modelContext: PersistenceModelContext,
    ): PersistenceAttribute =
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
}

/** Used for both schema objects and generated association rows. */
internal fun buildPersistenceEntity(
    graphqlName: String,
    generatedGlobalId: Boolean,
    attributes: List<PersistenceAttribute>,
): PersistenceEntity {
    val stored =
        if (generatedGlobalId) {
            listOf(PersistenceBasicAttribute("internalId", false, "java.util.UUID")) + attributes
        } else {
            attributes
        }
    require(stored.map { it.name }.distinct().size == stored.size) {
        "Generated fields collide at $graphqlName"
    }
    return PersistenceEntity(graphqlName, generatedGlobalId, stored)
}

/** One association-row shape; the Hibernate mapper supplies any legacy column names. */
internal fun buildAssociationEntity(
    typeName: String,
    ownerType: String,
    targets: List<PersistenceToOneAttribute>,
    edgeAttributes: List<PersistenceAttribute>,
    includeIdField: Boolean = false,
): PersistenceEntity =
    buildPersistenceEntity(
        typeName,
        generatedGlobalId = true,
        attributes =
            listOfNotNull(
                PersistenceBasicAttribute("id", false, "String").takeIf { includeIdField },
                PersistenceToOneAttribute("owner", false, ownerType),
            ) + targets + edgeAttributes,
    )
