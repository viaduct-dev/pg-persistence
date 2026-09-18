package dev.viaduct.persistence.hibernate

import dev.viaduct.persistence.model.PersistenceAttribute
import dev.viaduct.persistence.model.PersistenceBasicAttribute
import dev.viaduct.persistence.model.PersistenceEntity
import dev.viaduct.persistence.model.PersistenceToManyAttribute
import dev.viaduct.persistence.model.PersistenceToManyStorage
import dev.viaduct.persistence.model.PersistenceToOneAttribute
import org.hibernate.mapping.ManyToOne
import org.hibernate.mapping.PersistentClass

internal class RelationshipProjection(
    relationships: List<EffectiveHibernateRelationship>,
    computedRelationships: List<EffectiveHibernateComputedRelationship>,
) {
    val relationships: List<EffectiveHibernateRelationship> = java.util.List.copyOf(relationships)
    val computedRelationships: List<EffectiveHibernateComputedRelationship> =
        java.util.List.copyOf(computedRelationships)
}

/** Projects foreign-key relationships and join-table relationships from Hibernate metadata. */
internal class EffectiveHibernateRelationshipProjector(
    private val context: HibernateModelContext,
) {
    fun project(): RelationshipProjection {
        val computedRelationships = mutableListOf<EffectiveHibernateComputedRelationship>()
        val relationships =
            context.semanticModel.entities.flatMap { entity ->
                val binding = context.bindingFor(entity)
                entity.attributes.mapNotNull { attribute ->
                    relationshipFor(entity, attribute, binding, computedRelationships)
                }
            }
        return RelationshipProjection(relationships, computedRelationships)
    }

    private fun relationshipFor(
        entity: PersistenceEntity,
        attribute: PersistenceAttribute,
        binding: PersistentClass,
        computedRelationships: MutableList<EffectiveHibernateComputedRelationship>,
    ): EffectiveHibernateRelationship? =
        when (attribute) {
            is PersistenceToOneAttribute -> projectToOne(entity, attribute, binding)
            is PersistenceToManyAttribute ->
                projectToMany(
                    entity,
                    attribute,
                    binding,
                    computedRelationships,
                )
            else -> null
        }

    private fun projectToOne(
        entity: PersistenceEntity,
        attribute: PersistenceToOneAttribute,
        binding: PersistentClass,
    ): EffectiveHibernateRelationship? {
        // A scalar `@idOf` field already has its own GraphQL-visible name (e.g. `groupId`),
        // which pg_graphql also uses for the raw FK column itself. Naming the synthesized
        // single-object accessor the same would collide with that raw column in the generated
        // schema, so this relationship isn't given a `foreign_name` override; pg_graphql's
        // unreferenced default name is unused.
        val property = binding.requiredProperty(entity.graphqlName, attribute.name)
        val targetBinding = context.bindingFor(attribute.targetTypeName)
        return EffectiveHibernateRelationship(
            ownerTypeName = entity.graphqlName,
            fieldName = attribute.name,
            schemaName = property.value.table.schemaOrPublic(),
            tableName = property.value.table.name,
            columnName = property.singleColumnName(),
            graphqlNameKind =
                if (attribute.idOfDirected) GraphqlNameKind.NONE else GraphqlNameKind.FOREIGN,
            targetSchemaName = targetBinding.table.schemaOrPublic(),
            targetTableName = targetBinding.table.name,
            targetIdColumnName =
                targetBinding.identifier.singleColumnName(context.className(attribute.targetTypeName)),
        )
    }

    private fun projectToMany(
        entity: PersistenceEntity,
        attribute: PersistenceToManyAttribute,
        binding: PersistentClass,
        computedRelationships: MutableList<EffectiveHibernateComputedRelationship>,
    ): EffectiveHibernateRelationship? {
        val collection = context.collectionFor(entity.graphqlName, attribute.name)
        if (attribute.storage == PersistenceToManyStorage.TARGET_FOREIGN_KEY) {
            return projectTargetForeignKey(entity, attribute, binding, collection)
        }
        val targetBinding = context.bindingFor(attribute.targetTypeName)
        val element =
            collection.element as? ManyToOne
                ?: error(
                    "Hibernate mapping for ${entity.graphqlName}.${attribute.name} " +
                        "must use a many-to-many join table",
                )
        val ownerClassName = context.className(entity.graphqlName)
        val targetClassName = context.className(attribute.targetTypeName)
        computedRelationships +=
            EffectiveHibernateComputedRelationship(
                ownerTypeName = entity.graphqlName,
                fieldName = attribute.name,
                owner =
                    EffectiveHibernateTable(
                        schemaName = binding.table.schemaOrPublic(),
                        tableName = binding.table.name,
                        idColumnName = binding.identifier.singleColumnName(ownerClassName),
                    ),
                target =
                    EffectiveHibernateTable(
                        schemaName = targetBinding.table.schemaOrPublic(),
                        tableName = targetBinding.table.name,
                        idColumnName = targetBinding.identifier.singleColumnName(targetClassName),
                    ),
                join =
                    EffectiveHibernateJoinTable(
                        schemaName = collection.collectionTable.schemaOrPublic(),
                        tableName = collection.collectionTable.name,
                        ownerColumnName =
                            collection.key.singleColumnName(
                                "$ownerClassName.${attribute.name}",
                            ),
                        targetColumnName =
                            element.singleColumnName(
                                "$ownerClassName.${attribute.name}",
                            ),
                    ),
                edgeFields = projectEdgeFields(entity, attribute),
            )
        return null
    }

    private fun projectTargetForeignKey(
        entity: PersistenceEntity,
        attribute: PersistenceToManyAttribute,
        binding: PersistentClass,
        collection: org.hibernate.mapping.Collection,
    ): EffectiveHibernateRelationship =
        EffectiveHibernateRelationship(
            ownerTypeName = entity.graphqlName,
            fieldName = attribute.name,
            schemaName = collection.collectionTable.schemaOrPublic(),
            tableName = collection.collectionTable.name,
            columnName =
                collection.key.singleColumnName(
                    "${context.className(entity.graphqlName)}.${attribute.name}",
                ),
            graphqlNameKind = GraphqlNameKind.LOCAL,
            targetSchemaName = binding.table.schemaOrPublic(),
            targetTableName = binding.table.name,
            targetIdColumnName = binding.identifier.singleColumnName(context.className(entity.graphqlName)),
        )

    private fun projectEdgeFields(
        entity: PersistenceEntity,
        attribute: PersistenceToManyAttribute,
    ): List<EffectiveHibernateEdgeField> {
        if (attribute.edgeMapping == null) return emptyList()
        val association = context.associationFor(entity.graphqlName, attribute.name)
        val binding = context.associationBindingFor(entity.graphqlName, attribute.name)
        return association.edgeMapping.attributes.map { edgeAttribute ->
            val property = binding.requiredProperty(association.typeName, edgeAttribute.name)
            val column =
                property.value.columns.singleOrNull()
                    ?: error(
                        "Hibernate edge property ${association.typeName}.${edgeAttribute.name} " +
                            "must map to exactly one column",
                    )
            when (edgeAttribute) {
                is PersistenceBasicAttribute ->
                    EffectiveHibernateEdgeField(
                        name = edgeAttribute.name,
                        columnName = column.name,
                        sqlType = column.getSqlType(context.metadata),
                        nullable = edgeAttribute.nullable,
                    )
                is PersistenceToOneAttribute -> {
                    val target = context.bindingFor(edgeAttribute.targetTypeName)
                    EffectiveHibernateEdgeField(
                        name = edgeAttribute.name,
                        columnName = column.name,
                        sqlType = column.getSqlType(context.metadata),
                        nullable = edgeAttribute.nullable,
                        targetSchemaName = target.table.schemaOrPublic(),
                        targetTableName = target.table.name,
                        targetIdColumnName =
                            target.identifier.singleColumnName(
                                context.className(edgeAttribute.targetTypeName),
                            ),
                    )
                }
                is PersistenceToManyAttribute -> projectEdgeCollection(association, edgeAttribute)
            }
        }
    }

    private fun projectEdgeCollection(
        association: dev.viaduct.persistence.model.PersistenceAssociation,
        attribute: PersistenceToManyAttribute,
    ): EffectiveHibernateEdgeField {
        val collection = context.collectionFor(association.typeName, attribute.name)
        val targetBinding = context.bindingFor(attribute.targetTypeName)
        val targetClassName = context.className(attribute.targetTypeName)
        val targetIdColumn =
            targetBinding.identifier.columns.singleOrNull()
                ?: error("Hibernate identifier for ${attribute.targetTypeName} must map to one column")
        val target =
            EffectiveHibernateTable(
                schemaName = targetBinding.table.schemaOrPublic(),
                tableName = targetBinding.table.name,
                idColumnName = targetBinding.identifier.singleColumnName(targetClassName),
            )
        val ownerColumn =
            collection.key.singleColumnName(
                "${context.className(association.typeName)}.${attribute.name}",
            )
        return EffectiveHibernateEdgeField(
            name = attribute.name,
            columnName = ownerColumn,
            sqlType = targetIdColumn.getSqlType(context.metadata),
            nullable = attribute.nullable,
            targetSchemaName = target.schemaName,
            targetTableName = target.tableName,
            targetIdColumnName = target.idColumnName,
            collection = projectEdgeCollectionRelation(association, attribute, collection, target),
        )
    }

    private fun projectEdgeCollectionRelation(
        association: dev.viaduct.persistence.model.PersistenceAssociation,
        attribute: PersistenceToManyAttribute,
        collection: org.hibernate.mapping.Collection,
        target: EffectiveHibernateTable,
    ): EffectiveHibernateEdgeCollection {
        val ownerColumn =
            collection.key.singleColumnName("${context.className(association.typeName)}.${attribute.name}")
        return when (attribute.storage) {
            PersistenceToManyStorage.TARGET_FOREIGN_KEY ->
                EffectiveHibernateEdgeCollection(target = target, ownerColumnName = ownerColumn)
            PersistenceToManyStorage.JOIN_TABLE_OWNER,
            PersistenceToManyStorage.JOIN_TABLE_INVERSE,
            -> {
                val element =
                    collection.element as? ManyToOne
                        ?: error(
                            "Hibernate mapping for ${association.typeName}.${attribute.name} " +
                                "must use a many-to-many join table",
                        )
                EffectiveHibernateEdgeCollection(
                    target = target,
                    ownerColumnName = ownerColumn,
                    join =
                        EffectiveHibernateJoinTable(
                            schemaName = collection.collectionTable.schemaOrPublic(),
                            tableName = collection.collectionTable.name,
                            ownerColumnName = ownerColumn,
                            targetColumnName =
                                element.singleColumnName(
                                    "${context.className(association.typeName)}.${attribute.name}",
                                ),
                        ),
                )
            }
        }
    }
}
