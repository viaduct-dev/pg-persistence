package dev.viaduct.persistence.hibernate

import dev.viaduct.persistence.model.PersistenceBasicAttribute
import dev.viaduct.persistence.model.PersistenceEntity
import dev.viaduct.persistence.model.PersistenceToManyAttribute
import dev.viaduct.persistence.model.PersistenceToManyStorage
import dev.viaduct.persistence.model.PersistenceToOneAttribute
import org.hibernate.mapping.ManyToOne
import org.hibernate.mapping.OneToMany
import org.hibernate.mapping.ToOne
import org.hibernate.mapping.Collection as HibernateCollection

/** Verifies that generated Hibernate mappings still implement the semantic model. */
internal class HibernateSemanticModelValidator(
    private val context: HibernateModelContext,
) {
    fun validate() {
        validateIdentities()
        context.semanticModel.entities.forEach(::validateEntity)
    }

    private fun validateIdentities() {
        val entities = context.semanticModel.entities
        entities
            .groupBy { entity ->
                val table = context.bindingFor(entity).table
                table.schemaOrPublic() to table.name
            }.forEach { (table, owners) ->
                require(owners.size == 1) {
                    "Conflicting physical table ${table.first}.${table.second}: " +
                        owners.map { it.graphqlName }.sorted().joinToString()
                }
            }
        entities.groupBy { it.graphqlName.replaceFirstChar(Char::lowercase) }.forEach { (field, owners) ->
            require(owners.size == 1) {
                "Conflicting GraphQL collection ${field}Collection: " +
                    owners.map { it.graphqlName }.sorted().joinToString()
            }
        }
    }

    private fun validateEntity(entity: PersistenceEntity) {
        val binding = context.bindingFor(entity)
        entity.attributes.forEach { attribute ->
            when (attribute) {
                is PersistenceBasicAttribute -> validateBasic(entity, binding, attribute)
                is PersistenceToOneAttribute -> validateToOne(entity, binding, attribute)
                is PersistenceToManyAttribute -> validateToMany(entity, attribute)
            }
        }
    }

    private fun validateBasic(
        entity: PersistenceEntity,
        binding: org.hibernate.mapping.PersistentClass,
        attribute: PersistenceBasicAttribute,
    ) {
        val property = binding.requiredProperty(entity.graphqlName, attribute.name)
        require(property.value !is ToOne && property.value !is HibernateCollection) {
            "Hibernate mapping for ${entity.graphqlName}.${attribute.name} must be basic"
        }
        require(property.isOptional == attribute.nullable) {
            nullabilityError(entity, attribute.name)
        }
    }

    private fun validateToOne(
        entity: PersistenceEntity,
        binding: org.hibernate.mapping.PersistentClass,
        attribute: PersistenceToOneAttribute,
    ) {
        val property = binding.requiredProperty(entity.graphqlName, attribute.name)
        val association =
            property.value as? ToOne
                ?: error(
                    "Hibernate mapping for ${entity.graphqlName}.${attribute.name} must be to-one",
                )
        val expectedTarget = context.className(attribute.targetTypeName)
        require(association.referencedEntityName == expectedTarget) {
            "Hibernate mapping for ${entity.graphqlName}.${attribute.name} targets " +
                "${association.referencedEntityName}, expected $expectedTarget"
        }
        require(property.isOptional == attribute.nullable) {
            nullabilityError(entity, attribute.name)
        }
    }

    private fun validateToMany(
        entity: PersistenceEntity,
        attribute: PersistenceToManyAttribute,
    ) {
        val collection = context.collectionFor(entity.graphqlName, attribute.name)
        val expectedTarget = context.className(attribute.targetTypeName)
        val referencedEntityName =
            when (attribute.storage) {
                PersistenceToManyStorage.TARGET_FOREIGN_KEY ->
                    (collection.element as? OneToMany)?.referencedEntityName
                PersistenceToManyStorage.JOIN_TABLE_OWNER,
                PersistenceToManyStorage.JOIN_TABLE_INVERSE,
                -> (collection.element as? ManyToOne)?.referencedEntityName
            } ?: error(
                "Hibernate mapping for ${entity.graphqlName}.${attribute.name} has the " +
                    "wrong collection association type",
            )
        require(referencedEntityName == expectedTarget) {
            "Hibernate mapping for ${entity.graphqlName}.${attribute.name} targets " +
                "$referencedEntityName, expected $expectedTarget"
        }
    }

    private fun nullabilityError(
        entity: PersistenceEntity,
        fieldName: String,
    ): String = "Hibernate mapping for ${entity.graphqlName}.$fieldName changes GraphQL nullability"
}
