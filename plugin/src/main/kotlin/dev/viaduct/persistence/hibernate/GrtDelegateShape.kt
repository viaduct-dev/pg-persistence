package dev.viaduct.persistence.hibernate

import dev.viaduct.persistence.model.PersistenceEntity
import dev.viaduct.persistence.model.PersistenceModel
import dev.viaduct.persistence.model.PersistenceToOneAttribute
import dev.viaduct.persistence.model.buildAssociationEntity
import dev.viaduct.persistence.runtime.reflection.AbstractRelationship
import graphql.language.ObjectTypeDefinition
import graphql.schema.idl.TypeDefinitionRegistry

/** A mapping can represent a concrete GRT, an edge GRT, or a storage-only association row. */
internal class GrtDelegateShape(
    val entity: PersistenceEntity,
    val grtName: String?,
    val node: Boolean,
    abstractReferences: List<AbstractRelationship>,
) {
    val abstractReferences: List<AbstractRelationship> = java.util.List.copyOf(abstractReferences)
    val name get() = entity.graphqlName
    val className get() = name + if (grtName == null) "Row" else "Entity"
    val identityField get() = if (entity.generatedGlobalId) "internalId" else "id"
}

internal fun delegateShapes(
    model: PersistenceModel,
    registry: TypeDefinitionRegistry,
): List<GrtDelegateShape> {
    val entities =
        model.entities +
            model.associations.map {
                buildAssociationEntity(
                    it.typeName,
                    it.ownerTypeName,
                    listOf(PersistenceToOneAttribute("node", false, it.targetTypeName)),
                    it.edgeMapping.attributes,
                )
            }
    return entities.map { entity ->
        val reference =
            model.abstractTypes.relationships.singleOrNull {
                it.collection && it.rowType == entity.graphqlName
            }
        val edge =
            model.associations
                .singleOrNull { it.typeName == entity.graphqlName }
                ?.edgeMapping
                ?.typeName
                ?: reference?.edgeType
        val schemaObject = registry.getType(entity.graphqlName).orElse(null) is ObjectTypeDefinition
        val abstractFields =
            model.abstractTypes.relationships.filter {
                !it.collection && it.ownerType == entity.graphqlName
            }
        val edgeReference = reference?.takeIf { edge != null }?.copy(ownerType = entity.graphqlName, fieldName = "node")
        GrtDelegateShape(
            entity,
            if (schemaObject) entity.graphqlName else edge,
            schemaObject && entity.generatedGlobalId,
            abstractFields + listOfNotNull(edgeReference),
        )
    }
}
