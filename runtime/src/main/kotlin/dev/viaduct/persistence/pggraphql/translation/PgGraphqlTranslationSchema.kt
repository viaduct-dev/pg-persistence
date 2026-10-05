package dev.viaduct.persistence.pggraphql.translation

data class PgGraphqlFieldCoordinate(
    val parentType: String,
    val fieldName: String,
)

class PgGraphqlTranslationSchema(
    collectionElementTypes: Map<String, String>,
    fieldTypes: Map<PgGraphqlFieldCoordinate, String>,
    associationConnections: Set<PgGraphqlFieldCoordinate> = emptySet(),
    val abstractTypes: dev.viaduct.persistence.runtime.reflection.AbstractTypeMappings =
        dev.viaduct.persistence.runtime.reflection
            .AbstractTypeMappings(),
) {
    private val collectionElementTypeValues =
        java.util.Collections.unmodifiableMap(java.util.LinkedHashMap(collectionElementTypes))
    private val fieldTypeValues =
        java.util.Collections.unmodifiableMap(java.util.LinkedHashMap(fieldTypes))
    internal val associationConnectionValues =
        java.util.Collections.unmodifiableSet(java.util.LinkedHashSet(associationConnections))

    val collectionElementTypes: Map<String, String>
        get() = collectionElementTypeValues

    val fieldTypes: Map<PgGraphqlFieldCoordinate, String>
        get() = fieldTypeValues

    fun collectionElementType(typeName: String): String? = collectionElementTypes[typeName]

    /**
     * Returns the node type for a structural connection identified by its `edges.node` shape.
     */
    fun connectionNodeType(typeName: String): String? {
        val edgeType = fieldType(typeName, "edges") ?: return null
        return fieldType(edgeType, "node")
    }

    /**
     * Returns the element type for either a legacy Viaduct collection or a structural connection.
     */
    fun collectionNodeType(typeName: String): String? = collectionElementType(typeName) ?: connectionNodeType(typeName)

    fun fieldType(
        parentType: String,
        fieldName: String,
    ): String? = fieldTypes[PgGraphqlFieldCoordinate(parentType, fieldName)]

    fun isAssociationConnection(
        parentType: String,
        fieldName: String,
    ): Boolean =
        PgGraphqlAssociationConvention.isAssociationConnection(
            fieldTypes,
            parentType,
            fieldName,
            associationConnectionValues,
        )

    fun associationRowType(edgeType: String): String? =
        PgGraphqlAssociationConvention.rowType(fieldTypes, edgeType, associationConnectionValues)

    fun associationConnectionType(connectionType: String): String? =
        PgGraphqlAssociationConvention.connectionType(fieldTypes, connectionType, associationConnectionValues)

    override fun equals(other: Any?): Boolean =
        other is PgGraphqlTranslationSchema &&
            collectionElementTypes == other.collectionElementTypes &&
            fieldTypes == other.fieldTypes &&
            associationConnectionValues == other.associationConnectionValues &&
            abstractTypes == other.abstractTypes

    override fun hashCode(): Int =
        listOf(
            collectionElementTypes,
            fieldTypes,
            associationConnectionValues,
            abstractTypes,
        ).hashCode()

    override fun toString(): String =
        "PgGraphqlTranslationSchema(" +
            "collectionElementTypes=$collectionElementTypes, " +
            "fieldTypes=$fieldTypes, " +
            "associationConnections=$associationConnectionValues)"
}

/** Describes the concrete relationships after abstract selections have been translated. */
internal fun PgGraphqlTranslationSchema.storedAbstractSchema(): PgGraphqlTranslationSchema {
    val fields = fieldTypes.toMutableMap()
    abstractTypes.relationships.forEach { relationship ->
        relationship.targets.forEach { target ->
            fields[PgGraphqlFieldCoordinate(relationship.storageOwner, relationship.targetField(target))] = target
        }
        if (relationship.collection) {
            val connection = relationship.rowType + "Connection"
            val edge = relationship.rowType + "Edge"
            fields[PgGraphqlFieldCoordinate(relationship.ownerType, relationship.fieldName)] = connection
            fields[PgGraphqlFieldCoordinate(connection, "edges")] = edge
            fields[PgGraphqlFieldCoordinate(edge, "node")] = relationship.rowType
            val customFields =
                fieldTypes.filterKeys {
                    it.parentType == relationship.edgeType && it.fieldName != "node"
                }
            customFields.forEach { (field, type) ->
                fields[PgGraphqlFieldCoordinate(relationship.rowType, field.fieldName)] = type
            }
        }
    }
    val associations =
        associationConnectionValues
            .filterNot {
                abstractTypes.relationship(it.parentType, it.fieldName) !=
                    null
            }.toSet()
    return PgGraphqlTranslationSchema(collectionElementTypes, fields, associations, abstractTypes)
}
