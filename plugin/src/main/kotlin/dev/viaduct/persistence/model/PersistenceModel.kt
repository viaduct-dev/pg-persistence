package dev.viaduct.persistence.model

class PersistenceModel(
    entities: List<PersistenceEntity>,
    enums: List<PersistenceEnum>,
    semanticNotNullCoordinates: Set<String> = emptySet(),
    val abstractTypes: dev.viaduct.persistence.runtime.reflection.AbstractTypeMappings =
        dev.viaduct.persistence.runtime.reflection
            .AbstractTypeMappings(),
    val retryableTransactions: Boolean = false,
) {
    val entities: List<PersistenceEntity> = java.util.List.copyOf(entities)
    val enums: List<PersistenceEnum> = java.util.List.copyOf(enums)
    val semanticNotNullCoordinates: Set<String> = java.util.Set.copyOf(semanticNotNullCoordinates)
    val associations: List<PersistenceAssociation> =
        java.util.List.copyOf(
            this.entities
                .flatMap { entity ->
                    entity.attributes
                        .filterIsInstance<PersistenceToManyAttribute>()
                        .mapNotNull { attribute ->
                            attribute.edgeMapping?.let { mapping ->
                                PersistenceAssociation.from(entity.graphqlName, attribute, mapping)
                            }
                        }
                }.flatMap(::nestedAssociations),
        )

    override fun equals(other: Any?): Boolean {
        val candidate = other as? PersistenceModel ?: return false
        return entities == candidate.entities &&
            enums == candidate.enums &&
            semanticNotNullCoordinates == candidate.semanticNotNullCoordinates &&
            abstractTypes == candidate.abstractTypes &&
            retryableTransactions == candidate.retryableTransactions
    }

    override fun hashCode(): Int {
        var result = entities.hashCode()
        result = 31 * result + enums.hashCode()
        result = 31 * result + semanticNotNullCoordinates.hashCode()
        result = 31 * result + abstractTypes.hashCode()
        result = 31 * result + retryableTransactions.hashCode()
        return result
    }

    override fun toString(): String =
        "PersistenceModel(" +
            "entities=$entities, enums=$enums, semanticNotNull=$semanticNotNullCoordinates)"
}

class PersistenceEntity(
    val graphqlName: String,
    val generatedGlobalId: Boolean,
    attributes: List<PersistenceAttribute>,
) {
    val attributes: List<PersistenceAttribute> = java.util.List.copyOf(attributes)

    override fun equals(other: Any?): Boolean {
        val candidate = other as? PersistenceEntity ?: return false
        return graphqlName == candidate.graphqlName &&
            generatedGlobalId == candidate.generatedGlobalId &&
            attributes == candidate.attributes
    }

    override fun hashCode(): Int {
        var result = graphqlName.hashCode()
        result = 31 * result + generatedGlobalId.hashCode()
        result = 31 * result + attributes.hashCode()
        return result
    }

    override fun toString(): String =
        "PersistenceEntity(" +
            "graphqlName=$graphqlName, " +
            "generatedGlobalId=$generatedGlobalId, " +
            "attributes=$attributes)"
}

class PersistenceEnum(
    val graphqlName: String,
    values: List<String>,
) {
    val values: List<String> = java.util.List.copyOf(values)

    override fun equals(other: Any?): Boolean {
        val candidate = other as? PersistenceEnum ?: return false
        return graphqlName == candidate.graphqlName && values == candidate.values
    }

    override fun hashCode(): Int = 31 * graphqlName.hashCode() + values.hashCode()

    override fun toString(): String = "PersistenceEnum(graphqlName=$graphqlName, values=$values)"
}

sealed interface PersistenceAttribute {
    val name: String
    val nullable: Boolean
}

class PersistenceEdgeMapping(
    val typeName: String,
    attributes: List<PersistenceAttribute>,
) {
    val attributes: List<PersistenceAttribute> = java.util.List.copyOf(attributes)

    override fun equals(other: Any?): Boolean =
        other is PersistenceEdgeMapping &&
            typeName == other.typeName &&
            attributes == other.attributes

    override fun hashCode(): Int = 31 * typeName.hashCode() + attributes.hashCode()

    override fun toString(): String = "PersistenceEdgeMapping(typeName=$typeName, attributes=$attributes)"
}

data class PersistenceBasicAttribute(
    override val name: String,
    override val nullable: Boolean,
    val kotlinType: String,
    val enumTypeName: String? = null,
    val collection: Boolean = false,
    val elementNullable: Boolean = false,
    val columnDefinition: String? = null,
) : PersistenceAttribute

data class PersistenceToOneAttribute(
    override val name: String,
    override val nullable: Boolean,
    val targetTypeName: String,
    /** True when this to-one relationship is directed by `@idOf` on a scalar `ID` field. */
    val idOfDirected: Boolean = false,
) : PersistenceAttribute

data class PersistenceToManyAttribute(
    override val name: String,
    override val nullable: Boolean,
    val targetTypeName: String,
    val inverseFieldName: String?,
    val storage: PersistenceToManyStorage = PersistenceToManyStorage.TARGET_FOREIGN_KEY,
    val joinTableName: String? = null,
    val edgeMapping: PersistenceEdgeMapping? = null,
    val keyColumnNameOverride: String? = null,
) : PersistenceAttribute

data class PersistenceAssociation(
    val typeName: String,
    val ownerTypeName: String,
    val fieldName: String,
    val targetTypeName: String,
    val tableName: String,
    val ownerColumnName: String,
    val targetColumnName: String,
    val edgeMapping: PersistenceEdgeMapping,
) {
    companion object {
        fun from(
            ownerTypeName: String,
            attribute: PersistenceToManyAttribute,
            edgeMapping: PersistenceEdgeMapping,
        ): PersistenceAssociation {
            require(attribute.storage == PersistenceToManyStorage.JOIN_TABLE_OWNER) {
                "Association-backed edge $ownerTypeName.${attribute.name} must own its join table"
            }
            val selfReferential = ownerTypeName == attribute.targetTypeName
            return PersistenceAssociation(
                typeName = associationTypeName(ownerTypeName, attribute.name),
                ownerTypeName = ownerTypeName,
                fieldName = attribute.name,
                targetTypeName = attribute.targetTypeName,
                tableName = requireNotNull(attribute.joinTableName),
                ownerColumnName = associationJoinColumnName(ownerTypeName, "owner", selfReferential),
                targetColumnName = associationJoinColumnName(attribute.targetTypeName, "target", selfReferential),
                edgeMapping = edgeMapping,
            )
        }
    }
}

private fun nestedAssociations(association: PersistenceAssociation): List<PersistenceAssociation> =
    listOf(association) +
        association.edgeMapping.attributes
            .filterIsInstance<PersistenceToManyAttribute>()
            .flatMap { attribute ->
                attribute.edgeMapping
                    ?.let { mapping ->
                        nestedAssociations(
                            PersistenceAssociation.from(association.typeName, attribute, mapping),
                        )
                    }.orEmpty()
            }

enum class PersistenceToManyStorage {
    TARGET_FOREIGN_KEY,
    JOIN_TABLE_OWNER,
    JOIN_TABLE_INVERSE,
}
