package dev.viaduct.persistence.hibernate

import dev.viaduct.persistence.model.PersistenceModel
import org.hibernate.boot.Metadata

/** Builds the normalized model consumed by the PostgreSQL and pg_graphql overlays. */
object EffectiveHibernateModelBuilder {
    fun build(
        metadata: Metadata,
        semanticModel: PersistenceModel,
    ): EffectiveHibernateModel =
        EffectiveHibernateModelAssembler(
            HibernateModelContext(metadata, semanticModel),
        ).build()
}

private class EffectiveHibernateModelAssembler(
    private val context: HibernateModelContext,
) {
    private val entityProjector = EffectiveHibernateEntityProjector(context)
    private val relationshipProjector = EffectiveHibernateRelationshipProjector(context)
    private val arrayProjector = EffectiveHibernateArrayProjector(context)

    fun build(): EffectiveHibernateModel {
        HibernateSemanticModelValidator(context).validate()
        val relationshipProjection = relationshipProjector.project()
        return EffectiveHibernateModel(
            abstractReferences =
                context.semanticModel.abstractTypes.relationships.map { relationship ->
                    val binding = context.bindingFor(relationship.storageOwner)
                    EffectiveAbstractReference(
                        binding.table.schemaOrPublic(),
                        binding.table.name,
                        relationship.fieldName,
                        relationship.nullable,
                        relationship.targets.associate { target ->
                            binding
                                .requiredProperty(relationship.storageOwner, relationship.targetField(target))
                                .singleColumnName() to relationship.targetIdField(target)
                        },
                        ownerIdColumnName =
                            if (relationship.collection) {
                                binding.requiredProperty(relationship.storageOwner, "owner").singleColumnName()
                            } else {
                                null
                            },
                    )
                },
            entities =
                context.semanticModel.entities
                    .map(entityProjector::project)
                    .sortedBy(EffectiveHibernateEntity::graphqlName),
            relationships =
                relationshipProjection.relationships.sortedWith(
                    compareBy(
                        EffectiveHibernateRelationship::ownerTypeName,
                        EffectiveHibernateRelationship::fieldName,
                    ),
                ),
            computedRelationships =
                relationshipProjection.computedRelationships.sortedWith(
                    compareBy(
                        EffectiveHibernateComputedRelationship::ownerTypeName,
                        EffectiveHibernateComputedRelationship::fieldName,
                    ),
                ),
            arrays =
                arrayProjector.project().sortedWith(
                    compareBy(
                        EffectiveHibernateArray::ownerTypeName,
                        EffectiveHibernateArray::fieldName,
                    ),
                ),
        )
    }
}
