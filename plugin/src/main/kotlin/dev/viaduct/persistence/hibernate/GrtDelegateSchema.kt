package dev.viaduct.persistence.hibernate

import dev.viaduct.persistence.model.PersistenceEntity
import dev.viaduct.persistence.model.PersistenceModel
import dev.viaduct.persistence.model.PersistenceToOneAttribute
import graphql.language.FieldDefinition
import graphql.language.ListType
import graphql.language.NonNullType
import graphql.language.ObjectTypeDefinition
import graphql.language.Type
import graphql.language.TypeName
import graphql.schema.idl.TypeDefinitionRegistry

internal fun schemaFields(
    registry: TypeDefinitionRegistry,
    name: String,
): List<FieldDefinition> =
    (registry.getType(name).orElse(null) as? ObjectTypeDefinition)?.fieldDefinitions.orEmpty() +
        registry.objectTypeExtensions()[name].orEmpty().flatMap { it.fieldDefinitions }

internal fun Type<*>.unwrapNonNull(): Type<*> = if (this is NonNullType) type else this

internal fun Type<*>.baseName(): String =
    when (this) {
        is NonNullType -> type.baseName()
        is ListType -> type.baseName()
        else -> requireNotNull((this as TypeName).name)
    }

internal fun idAliases(
    entity: PersistenceEntity,
    registry: TypeDefinitionRegistry,
): Map<String, String> {
    val fields = schemaFields(registry, entity.graphqlName)
    return entity.attributes
        .filterIsInstance<PersistenceToOneAttribute>()
        .filterNot { it.idOfDirected }
        .mapNotNull { attribute ->
            fields
                .singleOrNull { it.name == "${attribute.name}Id" && it.directives.any { it.name == "idOf" } }
                ?.let { attribute.name to it.name }
        }.toMap()
}

internal fun validateAlias(
    field: PersistenceToOneAttribute,
    alias: String?,
): String {
    if (alias == null) return ""
    val read = "value.get${alias.replaceFirstChar(Char::uppercaseChar)}()"
    return """
        if (isGrtFieldSet { $read }) {
            val supplied = $read
            val expected = resolved_${field.name}?.let { executionContext().globalIDFor(${field.targetTypeName}Entity.BINDING.type, requireNotNull(it.internalId).toString()) }
            require(supplied == expected) { "Conflicting ${field.name} and $alias" }
        }
""".trimEnd()
}

/** Storage entities need native mappings, not invented GraphQL types. */
internal fun validateDelegateModel(
    model: PersistenceModel,
    registry: TypeDefinitionRegistry,
) {
    model.entities.forEach { entity ->
        val names = entity.attributes.map { it.name }
        require(names.map { it.replaceFirstChar(Char::uppercaseChar) }.distinct().size == names.size) {
            "Delegate property accessors collide on ${entity.graphqlName}"
        }
        if (registry.getType(entity.graphqlName).orElse(null) is ObjectTypeDefinition) {
            require(entity.generatedGlobalId || schemaFields(registry, entity.graphqlName).any { it.name == "id" }) {
                "Persisted object ${entity.graphqlName} needs its existing schema identity"
            }
        }
    }
}
