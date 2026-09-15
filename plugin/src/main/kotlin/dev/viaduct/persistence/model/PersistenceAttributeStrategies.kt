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
    val relationship: PersistenceRelationshipTarget?,
    val modelContext: PersistenceModelContext,
)

internal sealed interface PersistenceAttributeDecision {
    val attribute: PersistenceAttribute?

    data class Add(
        override val attribute: PersistenceAttribute,
    ) : PersistenceAttributeDecision

    data object Skip : PersistenceAttributeDecision {
        override val attribute: PersistenceAttribute? = null
    }
}

internal interface PersistenceAttributeStrategy {
    fun tryBuild(context: PersistenceAttributeContext): PersistenceAttributeDecision?
}

private val PersistenceAttributeContext.nullable: Boolean
    get() = field.type.isNullable && !modelContext.isSemanticallyNonNull(source, field)

internal class ToManyAttributeStrategy : PersistenceAttributeStrategy {
    override fun tryBuild(context: PersistenceAttributeContext): PersistenceAttributeDecision? =
        context.relationship
            ?.takeIf { it.collection }
            ?.let { relationship ->
                val target = context.modelContext.includedObjects.getValue(relationship.targetName)
                val edgeMapping = context.modelContext.edgeMapping(relationship.edgeTypeName)
                val mapping =
                    context.modelContext.collectionMapping(
                        context.source,
                        context.field,
                        target,
                        hasPersistedEdgeFields = edgeMapping != null,
                    )
                PersistenceAttributeDecision.Add(
                    PersistenceToManyAttribute(
                        name = context.field.name,
                        nullable = context.nullable,
                        targetTypeName = relationship.targetName,
                        inverseFieldName = mapping.inverseFieldName,
                        storage = mapping.storage,
                        joinTableName = mapping.joinTableName,
                        edgeMapping = edgeMapping,
                    ),
                )
            }
}

internal class ToOneAttributeStrategy : PersistenceAttributeStrategy {
    override fun tryBuild(context: PersistenceAttributeContext): PersistenceAttributeDecision? =
        context.relationship
            ?.takeUnless { it.collection }
            ?.let { relationship ->
                PersistenceAttributeDecision.Add(
                    PersistenceToOneAttribute(
                        name = context.field.name,
                        nullable = context.nullable,
                        targetTypeName = relationship.targetName,
                        idOfDirected = relationship.idOfDirected,
                    ),
                )
            }
}

internal class ResolverAttributeStrategy : PersistenceAttributeStrategy {
    override fun tryBuild(context: PersistenceAttributeContext): PersistenceAttributeDecision? =
        PersistenceAttributeDecision.Skip.takeIf {
            context.field.hasAppliedDirective("resolver")
        }
}

internal class GraphqlIdAttributeStrategy(
    private val generatedGlobalId: Boolean,
) : PersistenceAttributeStrategy {
    override fun tryBuild(context: PersistenceAttributeContext): PersistenceAttributeDecision? =
        if (context.field.name == "id") {
            PersistenceAttributeDecision.Add(
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
    override fun tryBuild(context: PersistenceAttributeContext): PersistenceAttributeDecision {
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
                    context.modelContext.generatedEnums.putIfAbsent(
                        baseType.name,
                        PersistenceEnum(
                            graphqlName = baseType.name,
                            values = baseType.values.map { it.name },
                        ),
                    )
                }
            } else {
                null
            }
        return PersistenceAttributeDecision.Add(
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
