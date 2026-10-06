package dev.viaduct.persistence.gradle

import dev.viaduct.persistence.model.PersistenceBasicAttribute
import dev.viaduct.persistence.model.PersistenceEntity
import dev.viaduct.persistence.model.PersistenceModel
import dev.viaduct.persistence.model.PersistenceToOneAttribute
import graphql.language.ArrayValue
import graphql.language.Directive
import graphql.language.ObjectTypeDefinition
import graphql.language.StringValue
import graphql.schema.idl.TypeDefinitionRegistry

/** The SDL is the source of single-column and composite database uniqueness. */
internal fun PersistenceModel.withUniqueConstraints(registry: TypeDefinitionRegistry): PersistenceModel {
    val entitiesWithKeys =
        entities.map { entity ->
            val definition = registry.types()[entity.graphqlName] as? ObjectTypeDefinition
            val directives =
                definition?.directives.orEmpty() +
                    registry.objectTypeExtensions()[entity.graphqlName].orEmpty().flatMap { it.directives }
            val keys =
                directives
                    .filter { it.name == "pgUnique" }
                    .map { uniqueKeyFields(it, entity) }
                    .distinct()
                    .sortedBy { it.joinToString(",") }
            PersistenceEntity(entity.graphqlName, entity.generatedGlobalId, entity.attributes, keys)
        }
    return PersistenceModel(entitiesWithKeys, enums, semanticNotNullCoordinates, abstractTypes, retryableTransactions)
}

private fun uniqueKeyFields(
    directive: Directive,
    entity: PersistenceEntity,
): List<String> {
    val fields =
        (directive.getArgument("fields")?.value as? ArrayValue)?.values.orEmpty().map { value ->
            requireNotNull((value as? StringValue)?.value) {
                "@pgUnique fields must be strings on ${entity.graphqlName}"
            }
        }
    require(fields.isNotEmpty() && fields.distinct().size == fields.size) {
        "@pgUnique on ${entity.graphqlName} requires distinct, nonempty fields"
    }
    fields.forEach { name ->
        val attribute = entity.attributes.singleOrNull { it.name == name }
        require(
            attribute is PersistenceToOneAttribute ||
                attribute is PersistenceBasicAttribute &&
                !attribute.collection &&
                name != "id",
        ) {
            "@pgUnique field ${entity.graphqlName}.$name must be a stored scalar or " +
                "to-one relationship"
        }
    }
    return fields.sorted()
}
