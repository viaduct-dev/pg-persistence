package dev.viaduct.persistence.hibernate

import dev.viaduct.persistence.model.PersistenceBasicAttribute
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
            val expected = resolved_${field.name}?.let { globalId(${field.targetTypeName}Entity.BINDING, it) }
            require(supplied == expected) { "Conflicting ${field.name} and $alias" }
        }
""".trimEnd()
}

/** Reject unproven representations rather than falling back to the existing provider. */
internal fun validateDelegateModel(
    model: PersistenceModel,
    registry: TypeDefinitionRegistry,
) {
    require(model.associations.isEmpty() && model.abstractTypes.relationships.isEmpty()) {
        "GRT delegates currently support concrete node relationships without custom edge storage"
    }
    model.entities.forEach { entity ->
        require(entity.generatedGlobalId && registry.getType(entity.graphqlName).orElse(null) is ObjectTypeDefinition) {
            "GRT delegates require schema-defined Node entities: ${entity.graphqlName}"
        }
        validatePropertyNames(entity)
        entity.attributes.filterIsInstance<PersistenceBasicAttribute>().forEach { field ->
            require(
                !(field.collection && field.columnDefinition == "jsonb") &&
                    field.kotlinType != "java.time.LocalTime",
            ) {
                "Unsupported delegate scalar ${entity.graphqlName}.${field.name}: " +
                    "JSON arrays and Time (which needs offset-preserving storage) are not yet supported"
            }
        }
    }
}

private fun validatePropertyNames(entity: PersistenceEntity) {
    val names = entity.attributes.map { it.name }
    val reserved = setOf("value", "binding", "context", "builder", "current", "pending", "class")
    require(
        names.none { it in reserved || it.startsWith("native_") },
    ) {
        "Delegate property conflicts with bridge state on ${entity.graphqlName}"
    }
    require(names.map { it.replaceFirstChar(Char::uppercaseChar) }.distinct().size == names.size) {
        "Delegate property accessors collide on ${entity.graphqlName}"
    }
}
