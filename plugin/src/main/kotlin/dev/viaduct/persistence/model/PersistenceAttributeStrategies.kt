package dev.viaduct.persistence.model

import viaduct.graphql.schema.ViaductSchema

private val SCALAR_KOTLIN_TYPES =
    mapOf(
        "String" to "String",
        "ID" to "java.util.UUID",
        "Date" to "java.time.LocalDate",
        "DateTime" to "java.time.OffsetDateTime",
        "Time" to "java.time.LocalTime",
        "Boolean" to "Boolean",
        "Byte" to "Byte",
        "Short" to "Short",
        "Int" to "Int",
        "Long" to "Long",
        "Float" to "Double",
        "BigDecimal" to "java.math.BigDecimal",
        "BigInteger" to "java.math.BigInteger",
        "JSON" to "String",
    )

private const val JSON_COLUMN_DEFINITION = "jsonb"

internal data class PersistenceAttributeContext(
    val source: ViaductSchema.Object,
    val field: ViaductSchema.Field,
    val relationship: PersistenceRelationship?,
    val modelContext: PersistenceModelContext,
)

/** Null means try the next strategy; an empty list means the field is not stored. */
internal interface PersistenceAttributeStrategy {
    fun tryBuild(context: PersistenceAttributeContext): List<PersistenceAttribute>?
}

/** Shared field processing for schema entities and custom connection edges. */
internal class PersistenceFieldAttributeFactory(
    generatedGlobalId: Boolean,
) {
    private val strategies =
        listOf(
            ToManyAttributeStrategy(),
            ToOneAttributeStrategy(),
            ResolverAttributeStrategy(),
            GraphqlIdAttributeStrategy(generatedGlobalId),
            BasicAttributeStrategy(),
        )

    fun build(
        type: ViaductSchema.Object,
        field: ViaductSchema.Field,
        modelContext: PersistenceModelContext,
    ): List<PersistenceAttribute> {
        val context = PersistenceAttributeContext(type, field, modelContext.relationships(type)[field], modelContext)
        return strategies.firstNotNullOf { it.tryBuild(context) }
    }
}

private val PersistenceAttributeContext.nullable: Boolean
    get() = field.type.isNullable && !modelContext.isSemanticallyNonNull(source, field)

internal class ToManyAttributeStrategy : PersistenceAttributeStrategy {
    override fun tryBuild(context: PersistenceAttributeContext): List<PersistenceAttribute>? {
        val relationship = context.relationship?.takeIf { it.collection } ?: return null
        val edgeMapping = context.modelContext.edgeMapping(relationship.edgeTypeName)
        val attribute =
            if (relationship.isAbstract) {
                val row =
                    buildAssociationEntity(
                        typeName = relationship.associationType,
                        ownerType = relationship.ownerType,
                        targets = relationship.targetAttributes(),
                        edgeAttributes = edgeMapping?.attributes.orEmpty(),
                        includeIdField = true,
                    )
                context.modelContext.register(row)
                PersistenceToManyAttribute(
                    relationship.fieldName,
                    true,
                    row.graphqlName,
                    "owner",
                    PersistenceToManyStorage.TARGET_FOREIGN_KEY,
                    keyColumnNameOverride = "ownerId",
                )
            } else {
                val mapping =
                    context.modelContext.collectionMapping(
                        context.source,
                        context.field,
                        context.modelContext.includedObjects.getValue(relationship.targetName),
                        hasPersistedEdgeFields = edgeMapping != null,
                    )
                PersistenceToManyAttribute(
                    name = context.field.name,
                    nullable = relationship.nullable,
                    targetTypeName = relationship.targetName,
                    inverseFieldName = mapping.inverseFieldName,
                    storage = mapping.storage,
                    joinTableName = mapping.joinTableName,
                    edgeMapping = edgeMapping,
                )
            }
        return listOf(attribute)
    }
}

internal class ToOneAttributeStrategy : PersistenceAttributeStrategy {
    override fun tryBuild(context: PersistenceAttributeContext): List<PersistenceAttribute>? =
        context.relationship?.takeUnless { it.collection }?.targetAttributes()
}

internal class ResolverAttributeStrategy : PersistenceAttributeStrategy {
    override fun tryBuild(context: PersistenceAttributeContext): List<PersistenceAttribute>? =
        emptyList<PersistenceAttribute>().takeIf { context.field.hasAppliedDirective("resolver") }
}

internal class GraphqlIdAttributeStrategy(
    private val generatedGlobalId: Boolean,
) : PersistenceAttributeStrategy {
    override fun tryBuild(context: PersistenceAttributeContext): List<PersistenceAttribute>? =
        if (context.field.name == "id") {
            listOf(
                PersistenceBasicAttribute(
                    name = "id",
                    nullable = context.nullable,
                    kotlinType = if (generatedGlobalId) "String" else "java.util.UUID",
                ),
            )
        } else {
            null
        }
}

internal class BasicAttributeStrategy : PersistenceAttributeStrategy {
    override fun tryBuild(context: PersistenceAttributeContext): List<PersistenceAttribute> {
        val baseType = context.field.type.baseTypeDef
        val basicType = basicKotlinType(baseType)
        require(basicType != null && context.field.type.listDepth <= 1) {
            "Persistent field ${context.source.name}.${context.field.name} cannot be represented by " +
                "the default Hibernate conventions. Mark a resolver-backed field with @resolver " +
                "or model the relationship explicitly."
        }
        val enumTypeName =
            if (baseType is ViaductSchema.Enum) {
                baseType.name.also {
                    context.modelContext.register(
                        PersistenceEnum(
                            graphqlName = baseType.name,
                            values = baseType.values.map { it.name },
                        ),
                    )
                }
            } else {
                null
            }
        return listOf(
            PersistenceBasicAttribute(
                name = context.field.name,
                nullable = context.nullable,
                kotlinType = enumTypeName?.let(::enumClassName) ?: basicType,
                enumTypeName = enumTypeName,
                collection = context.field.type.isList,
                elementNullable = context.field.type.isList && context.field.type.baseTypeNullable,
                columnDefinition =
                    if (baseType is ViaductSchema.Scalar && baseType.name == "JSON") {
                        JSON_COLUMN_DEFINITION
                    } else {
                        null
                    },
            ),
        )
    }
}

private fun basicKotlinType(type: ViaductSchema.TypeDef): String? =
    when (type) {
        is ViaductSchema.Enum -> type.name
        is ViaductSchema.Scalar -> SCALAR_KOTLIN_TYPES[type.name]
        else -> null
    }
