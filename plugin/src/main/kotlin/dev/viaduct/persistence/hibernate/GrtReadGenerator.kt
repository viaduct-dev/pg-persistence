package dev.viaduct.persistence.hibernate

import dev.viaduct.persistence.model.PersistenceBasicAttribute
import dev.viaduct.persistence.model.PersistenceModel
import dev.viaduct.persistence.model.PersistenceToManyAttribute
import dev.viaduct.persistence.model.PersistenceToOneAttribute
import graphql.language.ListType
import graphql.schema.idl.TypeDefinitionRegistry

/** Typed tuple-to-builder calls; Hibernate owns native types and SQL, rather than a second entity graph. */
internal class GrtReadGenerator(
    shapes: Map<String, GrtDelegateShape>,
) {
    private val shapes = java.util.Map.copyOf(shapes)

    @Suppress("LongMethod", "CyclomaticComplexMethod") // Complete typed callback, reusing scalar/relationship helpers.
    fun reader(
        shape: GrtDelegateShape,
        model: PersistenceModel,
        registry: TypeDefinitionRegistry,
        grtPackage: String,
    ): String {
        val name = requireNotNull(shape.grtName)
        val schema = schemaFields(registry, name).associateBy { it.name }
        val aliases = idAliases(shape.entity, registry)
        val columns = linkedMapOf<String, List<String>>()
        val writes = mutableListOf<String>()
        if ("id" in schema) {
            val id =
                if (shape.node) {
                    "context.globalIDFor($name.Reflection, row.identity.toString())"
                } else {
                    "row.identity.toString()"
                }
            writes += "        if (\"id\" in fields) result.id($id)"
        }
        shape.entity.attributes
            .filterIsInstance<PersistenceBasicAttribute>()
            .filter { it.name !in setOf("id", "internalId") }
            .forEach { field ->
                columns[field.name] = listOf(field.name)
                val elementType =
                    when {
                        field.columnDefinition == "jsonb" -> "Any"
                        field.enumTypeName != null -> "String"
                        else -> field.kotlinType
                    }
                val type =
                    if (field.collection) {
                        "Array<$elementType${if (field.elementNullable) "?" else ""}>"
                    } else {
                        elementType
                    }
                val raw = "row.value<$type?>(\"${field.name}\")"
                val value = required(scalarFromDatabase(field, raw, grtPackage), field.nullable)
                writes += "        if (\"${field.name}\" in fields) result.`${field.name}`($value)"
            }
        shape.entity.attributes
            .filterIsInstance<PersistenceToOneAttribute>()
            .filter { it.name in schema }
            .forEach { field ->
                val property = "${field.name}.${shapes.getValue(field.targetTypeName).identityField}"
                columns[field.name] = listOf(property)
                val value = reference(field.targetTypeName, field.name, "id", field.idOfDirected)
                writes += "        if (\"${field.name}\" in fields) result.`${field.name}`(" +
                    required("row.value<java.util.UUID?>(\"$property\")?.let { id -> $value }", field.nullable) + ")"
                aliases[field.name]?.let { alias ->
                    columns[alias] = listOf(property)
                    val id = reference(field.targetTypeName, alias, "id", true)
                    writes += "        if (\"$alias\" in fields) result.`$alias`(" +
                        required("row.value<java.util.UUID?>(\"$property\")?.let { id -> $id }", field.nullable) + ")"
                }
            }
        shape.abstractReferences.forEach { field ->
            val properties = field.targets.map { "${field.targetField(it)}.${shapes.getValue(it).identityField}" }
            columns[field.fieldName] = properties
            val values =
                field.targets.zip(properties).joinToString { (target, property) ->
                    "row.value<java.util.UUID?>(\"$property\")?.let { id -> " +
                        "${reference(target, field.fieldName, "id")} }"
                }
            val value =
                "listOfNotNull($values).also { require(it.size <= 1) { " +
                    "\"Conflicting ${field.fieldName} targets\" } }.singleOrNull()"
            writes += "        if (\"${field.fieldName}\" in fields) " +
                "result.`${field.fieldName}`(${required(value, field.nullable)})"
        }
        val relationships = GrtDelegateRelationships(shapes)
        shape.entity.attributes
            .filterIsInstance<PersistenceToManyAttribute>()
            .filter { schema[it.name]?.type?.unwrapNonNull() is ListType }
            .forEach {
                writes += relationships.collection(shape, it, model.abstractTypes.relationship(shape.name, it.name))
            }
        return """
GrtReadProjection(
            entityName = "${shape.name}",
            identityProperty = "${shape.identityField}",
            columns = mapOf<String, List<String>>(${columns.entries.joinToString { (field, properties) ->
            "\"$field\" to listOf(${properties.joinToString { "\"$it\"" }})"
        }}),
            build = { context, session, row, fields, selections ->
                val result = $name.Builder(context)
                val internalId = row.identity
${writes.joinToString("\n")}
                result.build()
            },
        )
""".trim()
    }

    private fun reference(
        target: String,
        field: String,
        id: String,
        idOnly: Boolean = false,
    ): String {
        val shape = shapes.getValue(target)
        val global = "context.globalIDFor(${shape.className}.BINDING.type, $id.toString())"
        return when {
            idOnly -> global
            shape.node -> "context.ref($global)"
            else ->
                "requireNotNull(${shape.className}.BINDING.reader).fetch(context, session, $id, " +
                    "${shape.className}.BINDING, selections.selectionSetFor(GRT.Fields.`$field`)" +
                    ".selectionSetFor(${shape.grtName}.Reflection))"
        }
    }
}
