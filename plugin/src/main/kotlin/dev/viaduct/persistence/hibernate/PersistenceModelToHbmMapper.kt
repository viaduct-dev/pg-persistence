package dev.viaduct.persistence.hibernate

import dev.viaduct.persistence.model.PersistenceAssociation
import dev.viaduct.persistence.model.PersistenceAttribute
import dev.viaduct.persistence.model.PersistenceBasicAttribute
import dev.viaduct.persistence.model.PersistenceEntity
import dev.viaduct.persistence.model.PersistenceModel
import dev.viaduct.persistence.model.PersistenceToManyAttribute
import dev.viaduct.persistence.model.PersistenceToManyStorage
import dev.viaduct.persistence.model.PersistenceToOneAttribute
import dev.viaduct.persistence.model.associationJoinColumnName
import dev.viaduct.persistence.model.buildAssociationEntity

/** Maps semantic persistence objects to a renderer-oriented native HBM document. */
internal object PersistenceModelToHbmMapper {
    private const val UNIQUE_KEY_DIGEST_BYTES = 12

    private val scalarSqlTypes =
        mapOf(
            "String" to "text",
            "Boolean" to "boolean",
            "Byte" to "smallint",
            "Short" to "smallint",
            "Int" to "integer",
            "Long" to "bigint",
            "Double" to "double precision",
            "java.util.UUID" to "uuid",
            "java.time.LocalDate" to "date",
            "java.time.LocalTime" to "time",
            "java.time.OffsetDateTime" to "timestamp with time zone",
            "java.math.BigDecimal" to "numeric",
        )

    private val scalarTypes =
        mapOf(
            "String" to "string",
            "Boolean" to "boolean",
            "Byte" to "byte",
            "Short" to "short",
            "Int" to "integer",
            "Long" to "long",
            "Double" to "double",
            "java.util.UUID" to "uuid",
        )

    fun map(model: PersistenceModel): HbmMappingDocument =
        HbmMappingDocument(
            entities =
                model.entities.map { mapEntity(it) } + model.associations.map(::mapAssociation) +
                    if (model.retryableTransactions) listOf(TransactionRecordMapping.entity()) else emptyList(),
        )

    private fun mapEntity(
        entity: PersistenceEntity,
        tableName: String = entity.graphqlName,
        columnNames: Map<String, String> = emptyMap(),
    ): HbmEntityMapping =
        HbmEntityMapping(
            entityName = entity.graphqlName,
            tableName = tableName,
            attributes =
                entity.attributes.map { attribute ->
                    val mapped = mapAttribute(entity, attribute, columnNames[attribute.name])
                    val keys =
                        entity.uniqueKeys
                            .filter { attribute.name in it }
                            .map { fields ->
                                val digest =
                                    java.security.MessageDigest
                                        .getInstance("SHA-256")
                                        .digest((entity.graphqlName + ":" + fields.joinToString(",")).toByteArray())
                                        .take(UNIQUE_KEY_DIGEST_BYTES)
                                        .joinToString("") { "%02x".format(it) }
                                "UK_$digest"
                            }.joinToString(",")
                            .ifEmpty { null }
                    when (mapped) {
                        is HbmBasicMapping -> mapped.copy(uniqueKey = keys)
                        is HbmToOneMapping -> mapped.copy(uniqueKey = keys)
                        else -> mapped
                    }
                },
        )

    private fun mapAssociation(association: PersistenceAssociation): HbmEntityMapping =
        mapEntity(
            entity =
                buildAssociationEntity(
                    typeName = association.typeName,
                    ownerType = association.ownerTypeName,
                    targets = listOf(PersistenceToOneAttribute("node", false, association.targetTypeName)),
                    edgeAttributes = association.edgeMapping.attributes,
                ),
            tableName = association.tableName,
            columnNames =
                mapOf(
                    "internalId" to "_viaduct_id",
                    "owner" to association.ownerColumnName,
                    "node" to association.targetColumnName,
                ),
        )

    private fun mapAttribute(
        entity: PersistenceEntity,
        attribute: PersistenceAttribute,
        columnName: String?,
    ): HbmAttributeMapping =
        when (attribute) {
            is PersistenceBasicAttribute -> mapBasic(entity, attribute, columnName ?: attribute.name)
            is PersistenceToOneAttribute ->
                mapToOne(
                    entity.graphqlName,
                    attribute.name,
                    attribute.targetTypeName,
                    columnName ?: if (attribute.idOfDirected) attribute.name else "${attribute.name}Id",
                    attribute.nullable,
                )
            is PersistenceToManyAttribute -> mapToMany(entity, attribute)
        }

    private fun mapBasic(
        entity: PersistenceEntity,
        attribute: PersistenceBasicAttribute,
        columnName: String,
    ): HbmBasicMapping {
        val primaryKey = attribute.name == "internalId" || (!entity.generatedGlobalId && attribute.name == "id")
        val generatedId = entity.generatedGlobalId && attribute.name == "id"
        return HbmBasicMapping(
            name = attribute.name,
            hibernateType = hibernateType(attribute),
            columnName = columnName,
            nullable = attribute.nullable,
            primaryKey = primaryKey,
            insertable = !generatedId,
            updatable = !generatedId,
            columnDefinition = columnDefinition(entity, attribute, primaryKey),
        )
    }

    private fun mapToOne(
        ownerTypeName: String,
        name: String,
        targetTypeName: String,
        columnName: String,
        nullable: Boolean = false,
    ): HbmToOneMapping =
        HbmToOneMapping(
            name = name,
            targetEntityName = targetTypeName,
            columnName = columnName,
            nullable = nullable,
            foreignKeyName = "FK_${ownerTypeName}_$name",
        )

    private fun mapToMany(
        entity: PersistenceEntity,
        attribute: PersistenceToManyAttribute,
    ): HbmToManyMapping {
        val targetForeignKey = attribute.storage == PersistenceToManyStorage.TARGET_FOREIGN_KEY
        val selfReferential = entity.graphqlName == attribute.targetTypeName
        return HbmToManyMapping(
            name = attribute.name,
            targetEntityName = attribute.targetTypeName,
            keyColumnName =
                if (targetForeignKey) {
                    attribute.keyColumnNameOverride ?: "${entity.graphqlName.replaceFirstChar(Char::lowercaseChar)}Id"
                } else {
                    associationJoinColumnName(entity.graphqlName, "owner", selfReferential)
                },
            inverse =
                attribute.inverseFieldName != null ||
                    attribute.storage == PersistenceToManyStorage.JOIN_TABLE_INVERSE,
            joinTableName = attribute.joinTableName.takeUnless { targetForeignKey },
            targetColumnName =
                associationJoinColumnName(attribute.targetTypeName, "target", selfReferential)
                    .takeUnless { targetForeignKey },
            targetForeignKeyName = "FK_${entity.graphqlName}_${attribute.name}_target".takeUnless { targetForeignKey },
        )
    }

    private fun hibernateType(attribute: PersistenceBasicAttribute): String =
        when {
            attribute.enumTypeName != null || attribute.collection -> "string"
            else -> scalarTypes[attribute.kotlinType] ?: attribute.kotlinType
        }

    private fun columnDefinition(
        entity: PersistenceEntity,
        attribute: PersistenceBasicAttribute,
        primaryKey: Boolean,
    ): String? =
        when {
            attribute.name == "internalId" || primaryKey && attribute.kotlinType == "java.util.UUID" ->
                "uuid default gen_random_uuid()"
            entity.generatedGlobalId && attribute.name == "id" -> "text"
            attribute.collection -> "${scalarSqlType(attribute)}[]"
            attribute.kotlinType == "String" -> attribute.columnDefinition ?: "text"
            else -> attribute.columnDefinition
        }

    private fun scalarSqlType(attribute: PersistenceBasicAttribute): String =
        attribute.columnDefinition ?: if (attribute.enumTypeName != null) {
            "text"
        } else {
            requireNotNull(scalarSqlTypes[attribute.kotlinType]) {
                "No PostgreSQL array type for ${attribute.kotlinType}"
            }
        }
}
